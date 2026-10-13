package tz.co.msingi.cims.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Principal's institution overview: enrolment, intake against places, fees, results and demand by programme. */
@IntegrationTest
class OverviewIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    @Test
    void thePrincipalSeesTheYearByProgrammeWithWhatNeedsAttention() throws Exception {
        String tenant = "O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "principal", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        AdmissionScenario s = new AdmissionScenario(mvc, json, tenant, inst);
        String officer = s.staffToken("officer"), registrar = s.staffToken("registrar"), principal = s.staffToken("principal");

        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 300);
        s.admit(officer, s.staffToken("chair"), a, b);
        s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());

        JsonNode o = s.body(s.call(get("/api/dashboard/overview"), principal, null).andExpect(status().isOk()));
        assertThat(o.at("/year/code").asText()).isEqualTo("2027/2028");
        JsonNode t = o.get("totals");
        assertThat(t.get("students").asLong()).isEqualTo(2);
        assertThat(t.get("newStudents").asLong()).isEqualTo(2);
        assertThat(t.get("places").asLong()).isEqualTo(100);
        assertThat(t.get("intakeRate").asDouble()).isEqualTo(0.02);
        assertThat(t.get("applicants").asLong()).isEqualTo(2);
        assertThat(t.get("windowPlaces").asLong()).isEqualTo(50);
        assertThat(t.has("collectionRate")).isFalse(); // nothing billed yet
        assertThat(t.has("passRate")).isFalse();       // no results published yet

        JsonNode row = o.get("programmes").get(0);
        assertThat(row.get("code").asText()).isEqualTo("BTCIT");
        assertThat(row.get("female").asLong() + row.get("male").asLong()).isEqualTo(2);
        assertThat(row.get("attention").toString()).contains("Intake below 50% of places").contains("Fewer applicants than places");
        assertThat(o.get("intakeByYear").get(0).get("value").asLong()).isEqualTo(2);
        assertThat(o.get("collectionsByMonth")).hasSize(12);
        assertThat(o.at("/thresholds/lowPass").asDouble()).isEqualTo(0.6);

        s.call(get("/api/dashboard/overview?academicYearId=999999"), principal, null).andExpect(status().isNotFound());
        s.call(get("/api/dashboard/overview"), registrar, null).andExpect(status().isForbidden());
        s.call(get("/api/dashboard/overview"), officer, null).andExpect(status().isOk()); // the fixture officer is also administrator
    }
}
