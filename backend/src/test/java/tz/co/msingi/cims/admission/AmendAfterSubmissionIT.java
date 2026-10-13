package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Applicants change their details, programme choices and other qualifications after submitting, while the window is open. */
@IntegrationTest
class AmendAfterSubmissionIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired AdmissionWindowRepository windows;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    Applicant a;
    String app;

    static final String PROFILE = """
            {"firstName":"x","surname":"y","sex":"F","dateOfBirth":"2006-01-01","nationality":"Tanzanian",
             "region":"%s","nextOfKinName":"Parent","nextOfKinPhone":"0713000111"}""";

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        s = new AdmissionScenario(mvc, json, tenant, data.institution(tenant, BigDecimal.ZERO));
        a = s.apply("S0101/0001");
        app = "/api/applicant/applications/" + a.applicationId();
    }

    @Test
    void detailsChoicesAndQualificationsCanChangeWhileTheWindowIsOpen() throws Exception {
        JsonNode before = s.body(s.call(get(app), a.token(), null));
        assertThat(before.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(before.get("amendable").asBoolean()).isTrue();
        assertThat(before.path("amendedAt").isMissingNode() || before.get("amendedAt").isNull()).isTrue();

        // details: changed, but not left incomplete
        s.call(put("/api/applicant/profile"), a.token(), PROFILE.formatted("Mwanza")).andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("Mwanza"));
        s.call(put("/api/applicant/profile"), a.token(), PROFILE.formatted("")).andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));

        // other qualifications can be added and removed, except one a chosen programme needs
        long other = s.body(s.call(post("/api/applicant/qualifications/other"), a.token(), """
                {"awardName":"Certificate in Computer Applications","institution":"VETA Dodoma","year":2021,"level":"CERTIFICATE"}""")
                .andExpect(status().isCreated())).get("id").asLong();
        s.call(delete("/api/applicant/qualifications/" + other), a.token(), null).andExpect(status().isNoContent());
        long sitting = s.body(s.call(post("/api/applicant/qualifications/necta"), a.token(),
                "{\"type\":\"CSEE\",\"indexNumber\":\"S0101/0002\",\"year\":2024}").andExpect(status().isCreated())).get("id").asLong();
        s.call(delete("/api/applicant/qualifications/" + sitting), a.token(), null).andExpect(status().isNoContent());

        // choices: changed, never emptied
        s.call(put(app + "/choices"), a.token(), "{\"windowProgrammeIds\":[]}").andExpect(status().isBadRequest());
        JsonNode after = s.body(s.call(put(app + "/choices"), a.token(), "{\"windowProgrammeIds\":[" + s.inst.windowProgrammeId() + "]}")
                .andExpect(status().isOk()));
        assertThat(after.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(after.get("referenceNo").asText()).isEqualTo(before.get("referenceNo").asText());
        assertThat(after.get("amendedAt").asText()).isNotBlank();

        // the office sees when it was last changed
        s.call(get("/api/admission/applications/" + a.applicationId()), s.staffToken("officer"), null)
                .andExpect(jsonPath("$.application.amendedAt").exists());
    }

    @Test
    void theRegistrationResultsAChoiceNeedsCannotBeRemoved() throws Exception {
        // a weaker applicant whose only eligibility comes from a second sitting
        String token = s.applicantToken("S0101/0002");
        s.call(post("/api/applicant/qualifications/necta"), token, "{\"type\":\"CSEE\",\"indexNumber\":\"S0101/0001\",\"year\":2024}")
                .andExpect(status().isCreated());
        long strong = s.body(s.call(get("/api/applicant/qualifications"), token, null)).get(1).get("id").asLong();
        s.call(put("/api/applicant/profile"), token, PROFILE.formatted("Arusha")).andExpect(status().isOk());
        long id = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + s.inst.windowId() + "}")).get("id").asLong();
        s.call(put("/api/applicant/applications/" + id + "/choices"), token, "{\"windowProgrammeIds\":[" + s.inst.windowProgrammeId() + "]}")
                .andExpect(status().isOk());
        s.call(post("/api/applicant/applications/" + id + "/submit"), token, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(delete("/api/applicant/qualifications/" + strong), token, null).andExpect(jsonPath("$.code").value("NEEDED_FOR_CHOICES"));
    }

    @Test
    void changesStopWhenTheWindowClosesOrSelectionStarts() throws Exception {
        s.call(post("/api/admission/windows/" + s.inst.windowId() + "/selection-rounds"), s.staffToken("officer"), null).andExpect(status().isOk());
        s.call(get(app), a.token(), null).andExpect(jsonPath("$.amendable").value(false));
        s.call(put(app + "/choices"), a.token(), "{\"windowProgrammeIds\":[" + s.inst.windowProgrammeId() + "]}")
                .andExpect(jsonPath("$.code").value("APPLICATION_LOCKED"));
        s.call(put("/api/applicant/profile"), a.token(), PROFILE.formatted("Mwanza")).andExpect(jsonPath("$.code").value("APPLICATION_SUBMITTED"));
    }

    @Test
    void changesStopWhenTheWindowCloses() throws Exception {
        TenantContext.runAs(s.inst.tenant().toInfo(), () -> tx.executeWithoutResult(t -> windows.findById(s.inst.windowId()).ifPresent(w -> {
            w.setOpensAt(OffsetDateTime.now().minusDays(3));
            w.setClosesAt(OffsetDateTime.now().minusMinutes(1));
        })));
        s.call(get(app), a.token(), null).andExpect(jsonPath("$.amendable").value(false));
        s.call(post("/api/applicant/qualifications/other"), a.token(), """
                {"awardName":"X","institution":"Y","year":2021,"level":"OTHER"}""").andExpect(jsonPath("$.code").value("APPLICATION_SUBMITTED"));
    }

    @Test
    void programmesFollowTheEntryLevelOfTheHighestQualification() throws Exception {
        // a Form Four leaver may apply for the NTA 4 programme of the test window
        String token = s.applicantToken("S0101/0011");
        JsonNode elig = s.body(s.call(get("/api/applicant/eligibility?windowId=" + s.inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isTrue();

        // with a verified NTA 4 award the next level is NTA 5, so the NTA 4 programme is no longer open
        long nta = s.body(s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS4111/0001/2020\",\"graduationYear\":2022}")
                .andExpect(status().isCreated())).get("id").asLong();
        elig = s.body(s.call(get("/api/applicant/eligibility?windowId=" + s.inst.windowId()), token, null));
        assertThat(elig.get(0).get("eligible").asBoolean()).isFalse();
        assertThat(elig.get(0).at("/eligibility/summary").asText()).isEqualTo(
                "Not at your entry level: with your NTA Level 4 award you can apply for NTA Level 5 programmes");
        s.call(put("/api/applicant/profile"), token, PROFILE.formatted("Arusha")).andExpect(status().isOk());
        long id = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + s.inst.windowId() + "}")).get("id").asLong();
        s.call(put("/api/applicant/applications/" + id + "/choices"), token, "{\"windowProgrammeIds\":[" + s.inst.windowProgrammeId() + "]}")
                .andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));

        // without the award the NTA 4 programme opens again; after submitting, adding the award back is refused
        s.call(delete("/api/applicant/qualifications/" + nta), token, null).andExpect(status().isNoContent());
        s.call(put("/api/applicant/applications/" + id + "/choices"), token, "{\"windowProgrammeIds\":[" + s.inst.windowProgrammeId() + "]}")
                .andExpect(status().isOk());
        s.call(post("/api/applicant/applications/" + id + "/submit"), token, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(post("/api/applicant/qualifications/nta"), token, "{\"registrationNumber\":\"NS4111/0001/2020\",\"graduationYear\":2022}")
                .andExpect(jsonPath("$.code").value("NEEDED_FOR_CHOICES"));
        assertThat(s.body(s.call(get("/api/applicant/qualifications"), token, null)).findValuesAsText("type")).containsExactly("CSEE");
    }
}
