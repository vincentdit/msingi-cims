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
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Data subject requests and retention (BR-38 / FR-ADM-S06 / NFR-PRV-01). */
@IntegrationTest
class PrivacyIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, registrar;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
    }

    private <T> T db(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    @Test
    void anApplicantDownloadsTheirDataAndIsErasedOnRequest() throws Exception {
        String applicant = s.applicantToken();
        JsonNode export = s.body(s.call(get("/api/account/privacy/export"), applicant, null).andExpect(status().isOk()));
        assertThat(export.get("account").get("user_type").asText()).isEqualTo("APPLICANT");
        assertThat(export.get("applicant").get("surname").asText()).isNotBlank();
        assertThat(export.has("invoices")).isTrue();

        JsonNode req = s.body(s.call(post("/api/account/privacy/requests"), applicant,
                "{\"kind\":\"ERASURE\",\"details\":\"I joined another college; please delete my details.\"}").andExpect(status().isCreated()));
        assertThat(req.get("requestNo").asText()).matches("DSR-\\d{4}-0001");
        assertThat(req.get("erasable").asBoolean()).isTrue();
        s.call(post("/api/account/privacy/requests"), applicant, "{\"kind\":\"ERASURE\",\"details\":\"again\"}").andExpect(jsonPath("$.code").value("DSR_OPEN"));
        s.call(get("/api/privacy/requests?status=OPEN"), registrar, null).andExpect(jsonPath("$[0].kind").value("ERASURE"));
        s.call(get("/api/privacy/requests"), applicant, null).andExpect(status().isForbidden());

        s.call(post("/api/privacy/requests/" + req.get("id").asLong() + "/decision"), registrar,
                "{\"outcome\":\"COMPLETED\",\"response\":\"Your details have been erased.\",\"erase\":true}").andExpect(jsonPath("$.status").value("COMPLETED"));
        // the account is closed and nothing identifying is left
        s.call(get("/api/account/privacy/requests"), applicant, null).andExpect(status().isUnauthorized());
        Map<String, Object> row = db(() -> jdbc.queryForMap("""
                select a.first_name, a.email, a.date_of_birth, u.full_name, u.phone, u.status, u.anonymised_at from applicants a
                join user_accounts u on u.id = a.user_id where u.id = (select user_id from data_subject_requests where id = ?)""", req.get("id").asLong()));
        assertThat(row.get("first_name")).isEqualTo("Erased");
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("date_of_birth")).isNull();
        assertThat(row.get("status")).isEqualTo("DISABLED");
        assertThat(row.get("anonymised_at")).isNotNull();
    }

    @Test
    void studentsRecordsAreKeptAndRetentionClearsOldApplicants() throws Exception {
        String officer = s.staffToken("officer");
        String chair = s.staffToken("chair");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, chair, a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());

        JsonNode req = s.body(s.call(post("/api/account/privacy/requests"), a.token(), "{\"kind\":\"ERASURE\",\"details\":\"Delete me\"}")
                .andExpect(status().isCreated()));
        assertThat(req.get("erasable").asBoolean()).isFalse();
        s.call(post("/api/privacy/requests/" + req.get("id").asLong() + "/decision"), registrar,
                "{\"outcome\":\"COMPLETED\",\"response\":\"x\",\"erase\":true}").andExpect(jsonPath("$.code").value("DSR_NOT_ERASABLE"));
        s.call(post("/api/privacy/requests/" + req.get("id").asLong() + "/decision"), registrar,
                "{\"outcome\":\"REJECTED\",\"response\":\"Academic records are kept as NACTVET requires.\",\"erase\":false}")
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // an applicant who applied long ago and never joined, and an old text message
        String old = s.applicantToken();
        db(() -> {
            jdbc.update("update applicants set updated_at = now() - interval '30 months' where user_id = (select max(id) from user_accounts where user_type = 'APPLICANT')");
            jdbc.update("update applications set updated_at = now() - interval '30 months' where applicant_id = (select a.id from applicants a where a.user_id = (select max(id) from user_accounts where user_type = 'APPLICANT'))");
            return jdbc.update("update messages set queued_at = now() - interval '30 months' where id = (select min(id) from messages)");
        });
        JsonNode preview = s.body(s.call(get("/api/privacy/retention"), admin, null).andExpect(status().isOk()));
        assertThat(preview.get("applicantsToAnonymise").asInt()).isEqualTo(1);
        assertThat(preview.get("messagesToClear").asInt()).isGreaterThanOrEqualTo(1);
        s.call(post("/api/privacy/retention/run"), admin, null).andExpect(jsonPath("$.applicantsAnonymised").value(1));
        s.call(get("/api/account/privacy/requests"), old, null).andExpect(status().isUnauthorized());
        s.call(get("/api/account/privacy/requests"), a.token(), null).andExpect(status().isOk());   // the student is untouched
        s.call(get("/api/privacy/retention"), admin, null).andExpect(jsonPath("$.applicantsToAnonymise").value(0))
                .andExpect(jsonPath("$.lastRuns[0].applicants_anonymised").value(1));
    }
}
