package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.auth.PlatformUser;
import tz.co.msingi.cims.console.auth.PlatformUserRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Msingi platform console: platform sign-in, onboarding and supporting institutions, integration health. */
@IntegrationTest
class PlatformConsoleIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired PlatformUserRepository platformUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    String platform;
    String suffix;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
        platformUser("ops" + suffix, "Ops@2026x", false);
        platform = login("ops" + suffix, "Ops@2026x").get("accessToken").asText();
    }

    void platformUser(String username, String password, boolean mustChange) {
        tx.executeWithoutResult(s -> {
            PlatformUser u = new PlatformUser();
            u.setUsername(username);
            u.setFullName("Platform " + username);
            u.setPasswordHash(encoder.encode(password));
            u.setMustChangePassword(mustChange);
            platformUsers.save(u);
        });
    }

    JsonNode login(String username, String password) throws Exception {
        return body(call(post("/api/platform/auth/login"), null, null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").andExpect(status().isOk()));
    }

    ResultActions call(MockHttpServletRequestBuilder req, String token, String tenant, String body) throws Exception {
        if (tenant != null) req.header("X-Tenant", tenant);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    String onboardBody(String code, String host, String admin) {
        return """
                {"code":"%s","name":"%s Polytechnic College","shortName":"%sPC","nactvetRegNo":"REG/%s/009","ownership":"PRIVATE",
                 "contactEmail":"info@college.ac.tz","contactPhone":"+255 754 000 111","primaryColor":"#0B6E4F",
                 "domains":["https://%s/"],"admin":{"username":"%s","fullName":"Grace Mwakyusa","email":"grace@college.ac.tz","phone":"0754000222"}}"""
                .formatted(code, code, code, code, host, admin);
    }

    @Test
    void platformAndInstitutionSignInsAreKeptApart() throws Exception {
        String code = "Q" + suffix.toUpperCase();
        TestData.Institution inst = data.institution(code, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        String staff = new AdmissionScenario(mvc, json, code, inst).staffToken("admin");

        // a platform token is refused in an institution, an institution token on the console
        call(get("/api/registry/students"), platform, code, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_REALM"));
        int refused = call(get("/api/platform/institutions"), staff, code, null).andReturn().getResponse().getStatus();
        assertThat(refused).isIn(401, 403);
        call(get("/api/platform/institutions"), null, null, null).andExpect(status().isUnauthorized());
        call(get("/api/platform/auth/me"), platform, null, null).andExpect(jsonPath("$.username").value("ops" + suffix));

        // wrong password, and a temporary password allows only the password change
        call(post("/api/platform/auth/login"), null, null, "{\"username\":\"ops" + suffix + "\",\"password\":\"nope\"}").andExpect(status().isUnauthorized());
        platformUser("new" + suffix, "Temp@2026x", true);
        String temp = login("new" + suffix, "Temp@2026x").get("accessToken").asText();
        call(get("/api/platform/institutions"), temp, null, null).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        String fresh = body(call(post("/api/platform/auth/password"), temp, null,
                "{\"currentPassword\":\"Temp@2026x\",\"newPassword\":\"Mine@2026x\"}").andExpect(status().isOk())).get("accessToken").asText();
        call(get("/api/platform/institutions"), fresh, null, null).andExpect(status().isOk());
        call(get("/api/platform/institutions"), temp, null, null).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
    }

    @Test
    void anInstitutionIsOnboardedSuspendedReactivatedAndSupported() throws Exception {
        String code = "N" + suffix.toUpperCase();
        String host = "apply." + suffix + ".example.ac.tz";
        JsonNode issued = body(call(post("/api/platform/institutions"), platform, null, onboardBody(code, host, "grace")).andExpect(status().isCreated()));
        long id = issued.at("/institution/id").asLong();
        String temporary = issued.get("temporaryPassword").asText();
        assertThat(issued.get("username").asText()).isEqualTo("grace");
        assertThat(issued.at("/institution/status").asText()).isEqualTo("ACTIVE");
        assertThat(issued.at("/institution/domains/0/host").asText()).isEqualTo(host);
        assertThat(issued.at("/institution/administrators/0/mustChangePassword").asBoolean()).isTrue();
        assertThat(issued.at("/institution/primaryColor").asText()).isEqualTo("#0B6E4F");
        assertThat(issued.at("/institution/setup").size()).isEqualTo(10);   // private: also "Payment accounts"
        assertThat(issued.get("signInUrl").asText()).contains(host);

        // the same code or address cannot be used twice; addresses must be bare host names
        call(post("/api/platform/institutions"), platform, null, onboardBody(code, "other." + host, "x1x")).andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        call(post("/api/platform/institutions"), platform, null, onboardBody("X" + code, host, "x1x")).andExpect(jsonPath("$.code").value("DOMAIN_TAKEN"));
        call(post("/api/platform/institutions"), platform, null, onboardBody("Y" + code, "bad host/path", "x1x")).andExpect(jsonPath("$.code").value("INVALID_DOMAIN"));
        assertThat(body(call(get("/api/platform/institutions"), platform, null, null)).findValuesAsText("code")).contains(code).doesNotContain("X" + code, "Y" + code);

        // the address now reaches the institution, and its administrator signs in with the temporary password
        JsonNode inst = body(mvc.perform(get("/api/public/institution").with(r -> { r.setServerName(host); return r; })).andExpect(status().isOk()));
        assertThat(inst.toString()).contains(code + " Polytechnic College");
        JsonNode adminLogin = body(call(post("/api/auth/login"), null, code, "{\"username\":\"grace\",\"password\":\"" + temporary + "\"}").andExpect(status().isOk()));
        assertThat(adminLogin.at("/user/mustChangePassword").asBoolean()).isTrue();
        String adminToken = body(call(post("/api/auth/password"), adminLogin.get("accessToken").asText(), code,
                "{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"Grace@2026x\"}").andExpect(status().isOk())).get("accessToken").asText();

        // the institution's own audit trail shows what Msingi did
        JsonNode trail = body(call(get("/api/audit/logs?action=INSTITUTION_ONBOARDED"), adminToken, code, null).andExpect(status().isOk()));
        assertThat(trail.toString()).contains("(Msingi platform)");

        // suspension needs a reason; while suspended the institution answers "temporarily unavailable"
        call(post("/api/platform/institutions/" + id + "/suspend"), platform, null, "{}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        call(post("/api/platform/institutions/" + id + "/suspend"), platform, null, "{\"reason\":\"Subscription unpaid since August\"}")
                .andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.statusReason").value("Subscription unpaid since August"));
        call(get("/api/public/institution"), null, code, null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSTITUTION_SUSPENDED"));
        call(get("/api/registry/students"), adminToken, code, null).andExpect(status().isServiceUnavailable());
        call(post("/api/platform/institutions/" + id + "/suspend"), platform, null, "{\"reason\":\"again\"}").andExpect(jsonPath("$.code").value("SAME_STATUS"));
        call(post("/api/platform/institutions/" + id + "/reactivate"), platform, null, "{\"reason\":\"Paid\"}").andExpect(jsonPath("$.status").value("ACTIVE"));
        call(get("/api/public/institution"), null, code, null).andExpect(status().isOk());

        // support: a locked-out administrator gets a temporary password; their sessions end
        long adminId = issued.at("/institution/administrators/0/id").asLong();
        JsonNode reset = body(call(post("/api/platform/institutions/" + id + "/administrators/" + adminId + "/reset-password"), platform, null, null)
                .andExpect(status().isOk()));
        call(get("/api/registry/students"), adminToken, code, null).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        call(post("/api/auth/login"), null, code, "{\"username\":\"grace\",\"password\":\"Grace@2026x\"}").andExpect(status().isUnauthorized());
        call(post("/api/auth/login"), null, code, "{\"username\":\"grace\",\"password\":\"" + reset.get("temporaryPassword").asText() + "\"}")
                .andExpect(status().isOk());
        JsonNode second = body(call(post("/api/platform/institutions/" + id + "/administrators"), platform, null,
                "{\"username\":\"juma\",\"fullName\":\"Juma Ally\"}").andExpect(status().isOk()));
        assertThat(second.at("/institution/administrators").size()).isEqualTo(2);
        call(post("/api/platform/institutions/" + id + "/administrators"), platform, null, "{\"username\":\"juma\",\"fullName\":\"Again\"}")
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        // domains: add another, and the last one cannot be removed
        JsonNode withTwo = body(call(post("/api/platform/institutions/" + id + "/domains"), platform, null,
                "{\"host\":\"students." + suffix + ".example.ac.tz\",\"purpose\":\"STUDENT\"}").andExpect(status().isOk()));
        assertThat(withTwo.get("domains").size()).isEqualTo(2);
        long firstDomain = withTwo.at("/domains/0/id").asLong(), secondDomain = withTwo.at("/domains/1/id").asLong();
        call(delete("/api/platform/institutions/" + id + "/domains/" + firstDomain), platform, null, null).andExpect(status().isOk());
        call(delete("/api/platform/institutions/" + id + "/domains/" + secondDomain), platform, null, null).andExpect(jsonPath("$.code").value("LAST_DOMAIN"));

        // SMS credit shows as the institution's balance; details change
        call(post("/api/platform/institutions/" + id + "/sms-credit"), platform, null, "{\"units\":5000,\"amountPaid\":125000,\"reference\":\"MSG-INV-0042\"}")
                .andExpect(jsonPath("$.usage.smsBalance").value(5000));
        call(put("/api/platform/institutions/" + id), platform, null, """
                {"name":"%s Polytechnic College","shortName":"NPC","ownership":"PUBLIC","primaryColor":"#123456","notes":"Contract to 2028"}""".formatted(code))
                .andExpect(jsonPath("$.ownership").value("PUBLIC")).andExpect(jsonPath("$.notes").value("Contract to 2028"));

        // the platform's own audit trail has every step, newest first
        JsonNode audit = body(call(get("/api/platform/audit?tenantId=" + id), platform, null, null).andExpect(status().isOk()));
        assertThat(audit.findValuesAsText("action")).contains("INSTITUTION_ONBOARDED", "INSTITUTION_SUSPENDED", "INSTITUTION_REACTIVATED",
                "INSTITUTION_ADMIN_PASSWORD_RESET", "DOMAIN_ADDED", "DOMAIN_REMOVED", "SMS_BUNDLE_CREDITED", "INSTITUTION_UPDATED");
        assertThat(audit.at("/content/0/actorName").asText()).isEqualTo("Platform ops" + suffix);
        call(get("/api/platform/overview"), platform, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.health.length()").value(12));
    }

    @Test
    void integrationCallsAreLoggedForThePlatformAndEachInstitutionsAuditor() throws Exception {
        String a = "I" + suffix.toUpperCase(), b = "J" + suffix.toUpperCase();
        TestData.Institution ia = data.institution(a, BigDecimal.ZERO), ib = data.institution(b, BigDecimal.ZERO);
        data.staff(ia.tenant(), "auditor", Role.AUDITOR);
        data.staff(ib.tenant(), "auditor", Role.AUDITOR);
        AdmissionScenario sa = new AdmissionScenario(mvc, json, a, ia), sb = new AdmissionScenario(mvc, json, b, ib);
        sa.lookup("S0101/0001");
        sa.lookup("S0101/0002");
        sb.lookup("S0101/0003");

        // each institution's auditor sees only its own calls
        JsonNode mine = sa.body(sa.call(get("/api/audit/integrations?system=NECTA"), sa.staffToken("auditor"), null).andExpect(status().isOk()));
        assertThat(mine.get("totalElements").asLong()).isEqualTo(2);
        assertThat(mine.at("/content/0/mode").asText()).isEqualTo("SIMULATED");
        assertThat(mine.at("/content/0/outcome").asText()).isEqualTo("SUCCESS");
        assertThat(mine.toString()).contains("S0101/0002").doesNotContain("S0101/0003");
        JsonNode health = sb.body(sb.call(get("/api/audit/integrations/health"), sb.staffToken("auditor"), null));
        JsonNode necta = null;
        for (JsonNode h : health) if (h.get("system").asText().equals("NECTA")) necta = h;
        assertThat(necta.get("calls").asLong()).isEqualTo(1);
        assertThat(necta.get("status").asText()).isEqualTo("OK");
        sa.call(get("/api/audit/integrations"), sa.staffToken("officer"), null).andExpect(status().isOk()); // the administrator too

        // the platform sees every institution's calls, with the institution named
        JsonNode all = body(call(get("/api/platform/integrations/calls?system=NECTA&size=200"), platform, null, null).andExpect(status().isOk()));
        assertThat(all.findValuesAsText("tenantCode")).contains(a, b);
        call(get("/api/platform/integrations/health?hours=1"), platform, null, null).andExpect(status().isOk());

        // a GePG notification with a bad signature is logged as a failed inbound call belonging to no institution
        mvc.perform(post("/api/integrations/gepg/payment-notification").contentType(MediaType.APPLICATION_JSON)
                .header("X-Gepg-Signature", "bad").content("{\"billId\":\"1-2-3\"}")).andExpect(status().isUnauthorized());
        Long rejected = TenantContext.callAs(ia.info(), () -> tx.execute(s ->
                jdbc.queryForObject("select count(*) from integration_calls where system = 'GEPG'", Long.class)));
        assertThat(rejected).isZero(); // not visible inside an institution
        JsonNode gepg = body(call(get("/api/platform/integrations/calls?system=GEPG&outcome=FAILURE"), platform, null, null));
        assertThat(gepg.toString()).contains("invalid signature");
    }

    @Test
    void platformStaffManageEachOther() throws Exception {
        JsonNode created = body(call(post("/api/platform/users"), platform, null,
                "{\"username\":\"Neema" + suffix + "\",\"fullName\":\"Neema Support\",\"email\":\"neema@msingi.co.tz\"}").andExpect(status().isOk()));
        long id = created.at("/user/id").asLong();
        assertThat(created.at("/user/username").asText()).isEqualTo("neema" + suffix);
        assertThat(created.get("temporaryPassword").asText()).hasSize(12);
        call(post("/api/platform/users"), platform, null, "{\"username\":\"neema" + suffix + "\",\"fullName\":\"x\"}").andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        long me = body(call(get("/api/platform/auth/me"), platform, null, null)).get("id").asLong();
        call(post("/api/platform/users/" + me + "/disable"), platform, null, null).andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
        call(post("/api/platform/users/" + id + "/disable"), platform, null, null).andExpect(jsonPath("$.status").value("DISABLED"));
        call(post("/api/platform/auth/login"), null, null, "{\"username\":\"neema" + suffix + "\",\"password\":\"" + created.get("temporaryPassword").asText() + "\"}")
                .andExpect(status().isUnauthorized());
        call(post("/api/platform/users/" + id + "/enable"), platform, null, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        JsonNode reset = body(call(post("/api/platform/users/" + id + "/reset-password"), platform, null, null).andExpect(status().isOk()));
        login("neema" + suffix, reset.get("temporaryPassword").asText());
    }
}
