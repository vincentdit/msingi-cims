package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.auth.PlatformUser;
import tz.co.msingi.cims.console.auth.PlatformUserRepository;
import tz.co.msingi.cims.support.IntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Onboarding institutions from the NACTVET institution/programme catalogue (as NOAS reads it), here the simulated one. */
@IntegrationTest
class NactvetCatalogueIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;

    ResultActions call(MockHttpServletRequestBuilder req, String token, String tenant, String body) throws Exception {
        if (tenant != null) req.header("X-Tenant", tenant);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void institutionsAreOnboardedFromTheNactvetCatalogueWithTheirProgrammes() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
        tx.executeWithoutResult(s -> {
            PlatformUser u = new PlatformUser();
            u.setUsername("cat" + suffix);
            u.setFullName("Catalogue Tester");
            u.setPasswordHash(encoder.encode("Cat@2026x"));
            u.setMustChangePassword(false);
            users.save(u);
        });
        String token = body(call(post("/api/platform/auth/login"), null, null, "{\"username\":\"cat" + suffix + "\",\"password\":\"Cat@2026x\"}"))
                .get("accessToken").asText();

        // sync: every institution and programme NACTVET lists; a second sync finds nothing new
        JsonNode run = body(call(post("/api/platform/nactvet/sync?wait=true"), token, null, null).andExpect(status().isOk()));
        assertThat(run.get("status").asText()).isEqualTo("DONE");
        assertThat(run.get("mode").asText()).isEqualTo("SIMULATED");
        assertThat(run.get("institutions").asInt()).isEqualTo(7);
        assertThat(run.get("newProgrammes").asInt()).isEqualTo(run.get("programmes").asInt()).isPositive();
        JsonNode again = body(call(post("/api/platform/nactvet/sync?wait=true"), token, null, null));
        assertThat(again.get("newInstitutions").asInt()).isZero();
        assertThat(again.get("withdrawn").asInt()).isZero();
        JsonNode st = body(call(get("/api/platform/nactvet/status"), token, null, null));
        assertThat(st.get("institutions").asLong()).isEqualTo(7);
        assertThat(st.get("regions").toString()).contains("Arusha");

        // browse and search
        JsonNode page = body(call(get("/api/platform/nactvet/institutions?q=jabali"), token, null, null));
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        JsonNode row = page.at("/content/0");
        assertThat(row.get("registrationNumber").asText()).isEqualTo("REG/SIM/103");
        assertThat(row.get("programmes").asLong()).isEqualTo(12);
        assertThat(row.get("minLevel").asInt()).isEqualTo(4);
        assertThat(row.get("maxLevel").asInt()).isEqualTo(6);
        assertThat(body(call(get("/api/platform/nactvet/institutions?ownership=Government"), token, null, null)).get("totalElements").asLong()).isEqualTo(3);
        long nid = row.get("id").asLong();
        JsonNode detail = body(call(get("/api/platform/nactvet/institutions/" + nid), token, null, null));
        assertThat(detail.at("/suggestion/code").asText()).isEqualTo("JTC");
        assertThat(detail.at("/suggestion/ownership").asText()).isEqualTo("PUBLIC");
        assertThat(detail.at("/suggestion/nactvetRegNo").asText()).isEqualTo("REG/SIM/103");
        JsonNode programmes = detail.get("programmes");
        assertThat(programmes.size()).isEqualTo(12);
        JsonNode tc = null;
        for (JsonNode p : programmes) if (p.get("name").asText().startsWith("Technician Certificate in Civil")) tc = p;
        assertThat(tc.get("ntaLevel").asInt()).isEqualTo(5);
        assertThat(tc.get("ntaEntryHolderLevel").asInt()).isEqualTo(4);
        assertThat(tc.get("totalCapacity").asInt()).isEqualTo(80);

        // onboard it with the civil engineering programmes
        List<Long> civil = new ArrayList<>();
        for (JsonNode p : programmes) if (p.get("name").asText().contains("Civil")) civil.add(p.get("id").asLong());
        String code = "JTC" + suffix.substring(0, 3).toUpperCase();
        String onboard = """
                {"code":"%s","name":"Jabali Technical College","shortName":"JTC","nactvetRegNo":"REG/SIM/103","ownership":"PUBLIC",
                 "domains":["cims.%s.example.ac.tz"],"admin":{"username":"ictadmin","fullName":"ICT Officer"},
                 "nactvetInstitutionId":%d,"importProgrammeIds":%s}""".formatted(code, suffix, nid, civil);
        JsonNode issued = body(call(post("/api/platform/institutions"), token, null, onboard).andExpect(status().isCreated()));
        long tenantId = issued.at("/institution/id").asLong();
        assertThat(issued.at("/institution/nactvet/id").asLong()).isEqualTo(nid);
        assertThat(issued.at("/institution/nactvet/notImported").asLong()).isEqualTo(9);
        assertThat(issued.at("/institution/usage/programmes").asLong()).isEqualTo(3);
        // the same catalogue entry cannot become a second institution
        call(post("/api/platform/institutions"), token, null, onboard.replace(code, code + "X").replace("cims." + suffix, "x." + suffix))
                .andExpect(jsonPath("$.code").value("ALREADY_ONBOARDED"));

        // the institution's administrator sees the programmes as NACTVET lists them
        String temp = issued.get("temporaryPassword").asText();
        JsonNode login = body(call(post("/api/auth/login"), null, code, "{\"username\":\"ictadmin\",\"password\":\"" + temp + "\"}"));
        String admin = body(call(post("/api/auth/password"), login.get("accessToken").asText(), code,
                "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"Ict@2026xx\"}")).get("accessToken").asText();
        JsonNode list = body(call(get("/api/academic/programmes"), admin, code, null).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(3);
        JsonNode cert = null, diploma = null;
        for (JsonNode p : list) {
            if (p.get("awardLevel").asInt() == 5) cert = p;
            if (p.get("awardLevel").asInt() == 6) diploma = p;
        }
        assertThat(cert.get("code").asText()).isEqualTo("CE5");
        assertThat(cert.get("awardName").asText()).isEqualTo("Technician Certificate (NTA Level 5)");
        assertThat(cert.get("durationSemesters").asInt()).isEqualTo(2);
        assertThat(cert.get("entryLevel").asInt()).isEqualTo(5);
        assertThat(cert.get("accreditedCapacity").asInt()).isEqualTo(80);
        assertThat(cert.get("nactvetCode").asText()).isNotBlank();
        assertThat(diploma.get("durationSemesters").asInt()).isEqualTo(6);
        assertThat(diploma.get("entryLevel").asInt()).isEqualTo(4);
        JsonNode depts = body(call(get("/api/academic/departments"), admin, code, null));
        assertThat(depts.findValuesAsText("name")).containsExactly("Science and Allied Technologies");

        // later, the rest are imported from the institution's page; importing again adds nothing
        JsonNode imported = body(call(post("/api/platform/institutions/" + tenantId + "/nactvet"), token, null, "{}").andExpect(status().isOk()));
        assertThat(imported.at("/result/created").asInt()).isEqualTo(9);
        assertThat(imported.at("/institution/nactvet/notImported").asLong()).isZero();
        assertThat(body(call(post("/api/platform/institutions/" + tenantId + "/nactvet"), token, null, "{}")).at("/result/created").asInt()).isZero();
        assertThat(body(call(get("/api/platform/nactvet/institutions?onboarded=yes"), token, null, null)).at("/content/0/tenantCode").asText()).isEqualTo(code);

        // the pull is in the integration log and the platform audit trail
        JsonNode calls = body(call(get("/api/platform/integrations/calls?system=NACTVET_CATALOGUE"), token, null, null));
        assertThat(calls.get("totalElements").asLong()).isEqualTo(2);
        assertThat(body(call(get("/api/platform/audit?action=NACTVET_CATALOGUE_SYNCED"), token, null, null)).get("totalElements").asLong()).isEqualTo(2);
    }
}
