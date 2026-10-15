package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Connected systems reach a read-only API with OAuth2 client credentials, limited to their scopes (BR-41 / FR-INT-09). */
@IntegrationTest
class ApiClientIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, admin;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
    }

    private ResultActions token(String tenantCode, String form, String basic) throws Exception {
        var req = post("/api/oauth/token").header("X-Tenant", tenantCode).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form);
        if (basic != null) req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(req);
    }

    @Test
    void aClientGetsATokenAndReadsOnlyWhatItsScopesAllow() throws Exception {
        JsonNode issued = s.body(s.call(post("/api/api-clients"), admin, "{\"name\":\"Moodle LMS\",\"scopes\":[\"students:read\",\"programmes:read\"]}")
                .andExpect(status().isCreated()));
        String id = issued.get("client").get("clientId").asText();
        String secret = issued.get("clientSecret").asText();
        assertThat(id).startsWith(tenant.toLowerCase() + "-");
        s.call(post("/api/api-clients"), admin, "{\"name\":\"X\",\"scopes\":[\"everything\"]}").andExpect(jsonPath("$.code").value("UNKNOWN_SCOPE"));

        // wrong secret, unknown grant, another institution
        token(tenant, "grant_type=client_credentials&client_id=" + id + "&client_secret=nope", null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
        token(tenant, "grant_type=password&client_id=" + id + "&client_secret=" + secret, null).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        token(tenant, "grant_type=client_credentials&scope=results:read", id + ":" + secret).andExpect(jsonPath("$.error").value("invalid_scope"));

        JsonNode t = s.body(token(tenant, "grant_type=client_credentials&client_id=" + id + "&client_secret=" + secret, null).andExpect(status().isOk()));
        assertThat(t.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(t.get("scope").asText()).isEqualTo("students:read programmes:read");
        String api = t.get("access_token").asText();
        // HTTP Basic works too, and a narrower scope can be asked for
        String narrow = s.body(token(tenant, "grant_type=client_credentials&scope=programmes:read", id + ":" + secret).andExpect(status().isOk()))
                .get("access_token").asText();

        s.call(get("/api/integration/v1/programmes"), api, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").exists());
        s.call(get("/api/integration/v1/students?size=10"), api, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        s.call(get("/api/integration/v1/students"), narrow, null).andExpect(status().isForbidden());
        s.call(get("/api/integration/v1/payments?since=2020-01-01T00:00:00Z"), api, null).andExpect(status().isForbidden());
        // an API token is not a staff session, and a staff session cannot use the integration API
        s.call(get("/api/security-settings"), api, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_REALM"));
        s.call(get("/api/integration/v1/programmes"), admin, null).andExpect(status().isForbidden());
        s.call(get("/api/api-clients"), admin, null).andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());

        // a new secret retires the old one; switching the client off stops its tokens
        String secret2 = s.body(s.call(post("/api/api-clients/" + issued.get("client").get("id").asLong() + "/secret"), admin, null)).get("clientSecret").asText();
        token(tenant, "grant_type=client_credentials", id + ":" + secret).andExpect(status().isUnauthorized());
        token(tenant, "grant_type=client_credentials", id + ":" + secret2).andExpect(status().isOk());
        s.call(put("/api/api-clients/" + issued.get("client").get("id").asLong() + "?active=false"), admin,
                "{\"name\":\"Moodle LMS\",\"scopes\":[\"students:read\",\"programmes:read\"]}").andExpect(jsonPath("$.active").value(false));
        s.call(get("/api/integration/v1/programmes"), api, null).andExpect(status().isUnauthorized());
        token(tenant, "grant_type=client_credentials", id + ":" + secret2).andExpect(status().isUnauthorized());

        // a client of one institution gets nothing from another
        String other = "B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        data.institution(other, BigDecimal.ZERO);
        token(other, "grant_type=client_credentials", id + ":" + secret2).andExpect(status().isUnauthorized());
    }
}
