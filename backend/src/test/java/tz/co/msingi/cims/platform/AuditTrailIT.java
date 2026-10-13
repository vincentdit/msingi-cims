package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Auditor (and the administrator) read the audit trail: who did what, when and from where. */
@IntegrationTest
class AuditTrailIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, auditor, registrar;
    long createdId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "auditor", Role.AUDITOR);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        auditor = s.staffToken("auditor");
        registrar = s.staffToken("registrar");
        createdId = s.body(s.call(post("/api/admin/users"), admin,
                "{\"username\":\"neema\",\"fullName\":\"Neema Kweka\",\"roles\":[{\"role\":\"LECTURER\"}]}")).get("user").get("id").asLong();
        s.call(post("/api/auth/login"), null, "{\"username\":\"registrar\",\"password\":\"Wrong@2026\"}").andExpect(status().isUnauthorized());
    }

    JsonNode logs(String query) throws Exception {
        return s.body(s.call(get("/api/audit/logs" + query), auditor, null).andExpect(status().isOk()));
    }

    @Test
    void theAuditorSeesWhoDidWhatWhenAndFromWhere() throws Exception {
        JsonNode created = logs("?entity=StaffUser&action=CREATE").get("content").get(0);
        assertThat(created.get("actorName").asText()).isEqualTo("Staff admin");
        assertThat(created.get("area").asText()).isEqualTo("Security");
        assertThat(created.get("entityId").asLong()).isEqualTo(createdId);
        assertThat(created.get("details").get("username").asText()).isEqualTo("neema");
        assertThat(created.get("ipAddress").asText()).isNotBlank();

        // failed sign-ins are recorded against the account, with the reason
        JsonNode failed = logs("?action=LOGIN_FAILED").get("content");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).get("actorName").asText()).isEqualTo("Staff registrar");
        assertThat(failed.get(0).get("details").get("reason").asText()).isEqualTo("wrong password");

        // filters: security events, a person, free text and a record's whole history
        assertThat(logs("?security=true").get("content").findValuesAsText("action")).contains("LOGIN", "LOGIN_FAILED", "CREATE");
        long adminId = created.get("actorUserId").asLong();
        assertThat(logs("?actorId=" + adminId).get("content").findValuesAsText("actorName")).containsOnly("Staff admin");
        assertThat(logs("?q=neema").get("totalElements").asLong()).isEqualTo(1);
        assertThat(logs("?entity=StaffUser&entityId=" + createdId).get("content").findValuesAsText("action")).containsExactly("CREATE");
        assertThat(logs("?area=Finance").get("totalElements").asLong()).isZero();

        JsonNode page = logs("?size=2&page=0");
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("totalElements").asLong()).isGreaterThan(2);

        s.call(get("/api/audit/logs?from=" + LocalDate.now().plusDays(2) + "&to=" + LocalDate.now()), auditor, null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        s.call(get("/api/audit/logs?from=2020-01-01&to=2026-01-01"), auditor, null).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
    }

    @Test
    void summaryAndFiltersShowRecentActivityAndSecurityEvents() throws Exception {
        s.call(get("/api/audit/summary"), auditor, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSignIns").value(1))
                .andExpect(jsonPath("$.accountChanges").value(1))
                .andExpect(jsonPath("$.days.length()").value(7));
        s.call(get("/api/audit/facets"), auditor, null)
                .andExpect(jsonPath("$.areas[0]").value("Security"))
                .andExpect(jsonPath("$.actions[?(@.key=='LOGIN_FAILED')].count").value(1));
    }

    @Test
    void exportIsCsvAndIsItselfRecorded() throws Exception {
        String csv = s.call(get("/api/audit/logs.csv?security=true"), auditor, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("When (EAT),Who,User ID,Action,Area,Record,Record ID,Details,From (IP)").contains("LOGIN_FAILED");
        JsonNode exported = logs("?action=AUDIT_EXPORTED").get("content").get(0);
        assertThat(exported.get("actorName").asText()).isEqualTo("Staff auditor");
        assertThat(exported.get("details").get("records").asInt()).isPositive();
    }

    @Test
    void theTrailIsReadOnlyLimitedToItsInstitutionAndToAuditorsAndAdministrators() throws Exception {
        s.call(get("/api/audit/logs"), admin, null).andExpect(status().isOk());
        s.call(get("/api/audit/logs"), registrar, null).andExpect(status().isForbidden());
        s.call(get("/api/audit/logs.csv"), registrar, null).andExpect(status().isForbidden());

        // another institution's auditor sees nothing of this one
        String other = "B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst2 = data.institution(other, BigDecimal.ZERO);
        data.staff(inst2.tenant(), "auditor", Role.AUDITOR);
        AdmissionScenario s2 = new AdmissionScenario(mvc, json, other, inst2);
        JsonNode theirs = s2.body(s2.call(get("/api/audit/logs?q=neema"), s2.staffToken("auditor"), null));
        assertThat(theirs.get("totalElements").asLong()).isZero();

        // entries cannot be changed or deleted, even directly in the database
        TenantContext.runAs(inst.tenant().toInfo(), () -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(st -> jdbcTemplate.update("update audit_logs set action = 'X'")))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> tx.executeWithoutResult(st -> jdbcTemplate.update("delete from audit_logs")))
                    .hasMessageContaining("append-only");
        });
    }
}
