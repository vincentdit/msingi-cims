package tz.co.msingi.cims.approvals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Deputy Principal (Academic) and other approvers see everything waiting for their decision in one list. */
@IntegrationTest
class ApprovalsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    JsonNode find(JsonNode inbox, String kind) {
        for (JsonNode i : inbox.get("items")) if (i.get("kind").asText().equals(kind)) return i;
        return null;
    }

    @Test
    void approversSeeWhatWaitsForThemAndCannotDecideTheirOwn() throws Exception {
        String tenant = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        AdmissionScenario s = new AdmissionScenario(mvc, json, tenant, inst);
        String officer = s.staffToken("officer"), chair = s.staffToken("chair"), hod = s.staffToken("hod");

        s.apply("S0101/0001");
        long roundId = s.body(s.call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), officer, null)
                .andExpect(status().isOk())).at("/round/id").asLong();
        long yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[],\"electiveRules\":[]}").andExpect(status().isCreated());

        // the Deputy Principal: the round to approve, and the curriculum still being prepared
        JsonNode mine = s.body(s.call(get("/api/approvals"), chair, null).andExpect(status().isOk()));
        JsonNode round = find(mine, "SELECTION");
        assertThat(round.get("id").asLong()).isEqualTo(roundId);
        assertThat(round.get("canAct").asBoolean()).isTrue();
        assertThat(round.get("body").asText()).isEqualTo("Academic Committee");
        assertThat(round.get("link").asText()).isEqualTo("/staff/windows/" + inst.windowId() + "/selection");
        JsonNode cur = find(mine, "CURRICULUM");
        assertThat(cur.get("canAct").asBoolean()).isFalse();
        assertThat(cur.get("note").asText()).contains("no modules yet");
        assertThat(mine.get("actionable").asLong()).isEqualTo(1);
        s.call(get("/api/approvals/count"), chair, null).andExpect(jsonPath("$.actionable").value(1));

        // whoever proposed the round sees it but cannot approve it
        JsonNode proposer = find(s.body(s.call(get("/api/approvals"), officer, null)), "SELECTION");
        assertThat(proposer.get("canAct").asBoolean()).isFalse();
        assertThat(proposer.get("note").asText()).contains("someone else approves");

        // a Head of Department and the Examinations Officer have no committee items; others are refused
        assertThat(find(s.body(s.call(get("/api/approvals"), hod, null)), "SELECTION")).isNull();
        assertThat(s.body(s.call(get("/api/approvals"), s.staffToken("exams"), null)).get("total").asLong()).isZero();
        s.call(get("/api/approvals"), s.staffToken("registrar"), null).andExpect(status().isForbidden());

        // once decided it leaves the list
        s.call(post("/api/admission/selection-rounds/" + roundId + "/approve"), chair, "{\"committeeMinuteRef\":\"AC/1\"}").andExpect(status().isOk());
        assertThat(find(s.body(s.call(get("/api/approvals"), chair, null)), "SELECTION")).isNull();
    }
}
