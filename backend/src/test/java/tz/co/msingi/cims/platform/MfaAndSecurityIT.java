package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.BreachedPasswords;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.mfa.Totp;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two-step sign-in with an authenticator app, the MFA policy, idle time-outs and common passwords (BR-36, NFR-SEC-01/02). */
@IntegrationTest
class MfaAndSecurityIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "admin2", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
    }

    private JsonNode login(String user) throws Exception {
        return s.body(s.call(post("/api/auth/login"), null,
                "{\"username\":\"" + user + "\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}").andExpect(status().isOk()));
    }

    private String now(String secret) {
        return Totp.code(secret, Instant.now().getEpochSecond() / 30);
    }

    /** Sets up the authenticator for the user and returns its secret and recovery codes. */
    private JsonNode[] enrol(String token) throws Exception {
        JsonNode setup = s.body(s.call(post("/api/auth/mfa/setup"), token, null).andExpect(status().isOk()));
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + setup.get("secret").asText());
        assertThat(setup.get("qrPng").asText()).startsWith("data:image/png;base64,");
        s.call(post("/api/auth/mfa/enable"), token, "{\"code\":\"000000\"}").andExpect(jsonPath("$.code").value("MFA_CODE_WRONG"));
        JsonNode on = s.body(s.call(post("/api/auth/mfa/enable"), token, "{\"code\":\"" + now(setup.get("secret").asText()) + "\"}")
                .andExpect(status().isOk()));
        assertThat(on.get("recoveryCodes")).hasSize(10);
        return new JsonNode[]{setup, on};
    }

    @Test
    void totpFollowsRfc6238() {
        // RFC 6238 appendix B, SHA-1 secret "12345678901234567890" (base32 below), T = 59 s -> 94287082 (last six digits)
        assertThat(Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1111111109L / 30)).isEqualTo("081804");
    }

    @Test
    void staffWithAnAuthenticatorSignInInTwoSteps() throws Exception {
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(x -> jdbc.update("update user_accounts set phone = '0754000111' where username = 'admin'")));
        String admin = s.staffToken("admin");
        s.call(get("/api/auth/mfa"), admin, null).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.policy").value("OPTIONAL"));
        JsonNode[] e = enrol(admin);
        String secret = e[0].get("secret").asText();
        String recovery = e[1].get("recoveryCodes").get(0).asText();

        // the password alone no longer signs in
        JsonNode first = login("admin");
        assertThat(first.path("accessToken").isMissingNode() || first.get("accessToken").isNull()).isTrue();
        String challenge = first.get("mfa").get("challenge").asText();
        assertThat(first.get("mfa").get("smsAvailable").asBoolean()).isTrue();
        s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + challenge + "\",\"code\":\"111111\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MFA_CODE_WRONG"));
        s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + challenge + "x\",\"code\":\"" + now(secret) + "\"}")
                .andExpect(jsonPath("$.code").value("MFA_CHALLENGE_EXPIRED"));
        String token = s.body(s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + challenge + "\",\"code\":\"" + now(secret) + "\"}")
                .andExpect(status().isOk())).get("accessToken").asText();
        s.call(get("/api/security-settings"), token, null).andExpect(status().isOk());
        s.call(post("/api/auth/mfa/sms"), null, "{\"challenge\":\"" + login("admin").get("mfa").get("challenge").asText() + "\"}")
                .andExpect(jsonPath("$.status").value("SENT"));

        // a recovery code works once
        String c2 = login("admin").get("mfa").get("challenge").asText();
        s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + c2 + "\",\"code\":\"" + recovery + "\"}").andExpect(status().isOk());
        String c3 = login("admin").get("mfa").get("challenge").asText();
        s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + c3 + "\",\"code\":\"" + recovery + "\"}")
                .andExpect(jsonPath("$.code").value("MFA_CODE_WRONG"));
        s.call(get("/api/auth/mfa"), token, null).andExpect(jsonPath("$.recoveryCodesLeft").value(9));
    }

    @Test
    void thePolicyMakesPrivilegedStaffSetItUpAndAnAdministratorCanResetIt() throws Exception {
        String admin2 = s.staffToken("admin2");
        String settings = """
                {"mfaPolicy":"PRIVILEGED","staffIdleMinutes":20,"studentIdleMinutes":45,"applicantIdleMinutes":60,
                 "retentionApplicantMonths":24,"retentionMessageMonths":12,"retentionEnabled":false}""";
        // whoever makes it compulsory must use it already
        s.call(put("/api/security-settings"), admin2, settings).andExpect(jsonPath("$.code").value("MFA_SETUP_FIRST"));
        String admin = s.staffToken("admin");
        String secret = enrol(admin)[0].get("secret").asText();
        String c = login("admin").get("mfa").get("challenge").asText();
        admin = s.body(s.call(post("/api/auth/mfa/verify"), null, "{\"challenge\":\"" + c + "\",\"code\":\"" + now(secret) + "\"}")).get("accessToken").asText();
        s.call(put("/api/security-settings"), admin, settings).andExpect(status().isOk()).andExpect(jsonPath("$.staffIdleMinutes").value(20));
        s.call(get("/api/public/institution"), null, null).andExpect(jsonPath("$.idleMinutes.staff").value(20))
                .andExpect(jsonPath("$.idleMinutes.student").value(45));

        // the bursar must set it up before doing anything else; a lecturer need not
        JsonNode b = login("bursar");
        assertThat(b.get("user").get("mfaSetupRequired").asBoolean()).isTrue();
        String bursar = b.get("accessToken").asText();
        s.call(get("/api/finance/fee-schemes"), bursar, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_SETUP_REQUIRED"));
        assertThat(login("lecturer").get("user").get("mfaSetupRequired").asBoolean()).isFalse();
        JsonNode[] e = enrol(bursar);
        String fresh = e[1].get("token").get("accessToken").asText();
        s.call(get("/api/finance/fee-schemes"), fresh, null).andExpect(status().isOk());
        s.call(post("/api/auth/mfa/disable"), fresh, "{\"password\":\"" + TestData.STAFF_PASSWORD + "\"}").andExpect(jsonPath("$.code").value("MFA_REQUIRED"));

        // lost phone: the administrator clears it, the bursar's sessions end and set-up starts again
        Long bursarId = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject("select id from user_accounts where username = 'bursar'", Long.class)));
        s.call(post("/api/admin/users/" + bursarId + "/mfa/reset"), admin, null).andExpect(status().is2xxSuccessful());
        s.call(get("/api/finance/fee-schemes"), fresh, null).andExpect(status().isUnauthorized());
        assertThat(login("bursar").get("user").get("mfaSetupRequired").asBoolean()).isTrue();
    }

    @Test
    void commonPasswordsAreRefused() throws Exception {
        assertThat(BreachedPasswords.isBreached("Tanzania@2024")).isTrue();
        assertThat(BreachedPasswords.isBreached("P@ssw0rd1")).isTrue();
        assertThat(BreachedPasswords.isBreached("Simba!12")).isTrue();
        assertThat(BreachedPasswords.isBreached("Kifaru#Mto47")).isFalse();
        String lecturer = s.staffToken("lecturer");
        s.call(post("/api/auth/password"), lecturer, "{\"currentPassword\":\"" + TestData.STAFF_PASSWORD + "\",\"newPassword\":\"Welcome@2026\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.newPassword").value(org.hamcrest.Matchers.containsString("too common")));
        s.call(post("/api/auth/password"), lecturer, "{\"currentPassword\":\"" + TestData.STAFF_PASSWORD + "\",\"newPassword\":\"Mbuyu#Kijani83\"}")
                .andExpect(status().isOk());
    }
}
