package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Admissions Office searches applicants, exports the list and prints application files (as NOAS). */
@IntegrationTest
class ApplicantSearchIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    TestData.Institution inst;
    String admissions;
    Applicant a, b;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admissions = s.staffToken("admissions");
        a = s.apply("S0101/0001");
        b = s.applyEligibleEndingIn(5, 300);
        s.call(post("/api/applicant/qualifications/other"), b.token(), """
                {"awardName":"Certificate in Computer Applications","institution":"VETA Dodoma","year":2021,"reference":"cca/2021/77","level":"CERTIFICATE"}""")
                .andExpect(status().isCreated());
    }

    JsonNode search(String query) throws Exception {
        return s.body(s.call(get("/api/admission/applicants" + query), admissions, null).andExpect(status().isOk()));
    }

    @Test
    void theOfficeFindsApplicantsByWhatTheyKnow() throws Exception {
        JsonNode all = search("?windowId=" + inst.windowId());
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        JsonNode row = search("?q=S0101/0001").get("content");
        assertThat(row).hasSize(1);
        assertThat(row.get(0).get("indexNumber").asText()).isEqualTo("S0101/0001/2025");
        assertThat(row.get(0).get("firstChoice").asText()).isEqualTo("BTCIT");
        assertThat(row.get(0).get("status").asText()).isEqualTo("SUBMITTED");

        assertThat(search("?programmeId=" + inst.programmeId() + "&firstChoice=true").get("totalElements").asLong()).isEqualTo(2);
        assertThat(search("?programmeId=999999").get("totalElements").asLong()).isZero();
        assertThat(search("?checksPending=true").get("content").findValuesAsText("id")).containsExactly(String.valueOf(b.applicationId()));
        assertThat(search("?checksPending=true").get("content").get(0).get("checksPending").asLong()).isEqualTo(1);
        assertThat(search("?status=SUBMITTED&feePaid=true").get("totalElements").asLong()).isEqualTo(2);
        assertThat(search("?sex=F").get("totalElements").asLong()).isEqualTo(2); // the scenario's applicants are female
        assertThat(search("?sex=M").get("totalElements").asLong()).isZero();
        s.call(get("/api/admission/applicants?sex=X"), admissions, null).andExpect(status().isUnprocessableEntity());
        s.call(get("/api/admission/applicants?from=2030-01-02&to=2030-01-01"), admissions, null).andExpect(status().isUnprocessableEntity());
        s.call(get("/api/admission/applicants"), s.staffToken("bursar"), null).andExpect(status().isForbidden());
    }

    @Test
    void theListExportsAndTheApplicationFilePrintsAndBothAreRecorded() throws Exception {
        String csv = s.call(get("/api/admission/applicants.csv?windowId=" + inst.windowId()), admissions, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Reference,Surname,First name").contains("S0101/0001/2025").contains("BTCIT");
        assertThat(csv.split("\n")).hasSize(3);

        byte[] pdf = s.call(get("/api/admission/applications/" + a.applicationId() + "/file"), admissions, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        JsonNode history = s.body(s.call(get("/api/admission/applications/" + a.applicationId()), admissions, null)).get("history");
        assertThat(history.findValuesAsText("action")).contains("REGISTER", "SUBMIT", "APPLICATION_FILE_PRINTED");
        assertThat(history.findValuesAsText("label")).contains("Application submitted", "Application file printed");
    }
}
