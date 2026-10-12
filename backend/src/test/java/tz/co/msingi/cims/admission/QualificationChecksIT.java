package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Admissions Office checks declared qualifications against the certificates. */
@IntegrationTest
class QualificationChecksIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String officer, registrar, viewer;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "K" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        s = new AdmissionScenario(mvc, json, tenant, data.institution(tenant, BigDecimal.ZERO));
        data.staff(s.inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(s.inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        viewer = s.staffToken("viewer");
    }

    @Test
    void declaredQualificationsAreVerifiedOrNotAccepted() throws Exception {
        String token = s.applicantToken();
        long appId = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + s.inst.windowId() + "}")).get("id").asLong();
        long nva = s.body(s.call(post("/api/applicant/qualifications/prior-award"), token,
                "{\"type\":\"NVA\",\"number\":\"VETA/2021/123\",\"level\":3,\"year\":2021}").andExpect(status().isCreated())).get("id").asLong();
        long other = s.body(s.call(post("/api/applicant/qualifications/other"), token, """
                {"awardName":"Certificate in Computer Applications","institution":"VETA Dodoma","year":2021,"level":"CERTIFICATE"}""")
                .andExpect(status().isCreated())).get("id").asLong();
        long csee = s.body(s.call(get("/api/applicant/qualifications"), token, null)).get(0).get("id").asLong();

        // the office's queue: declared qualifications of the window's applicants, with whose application they are on
        String url = "/api/admission/windows/" + s.inst.windowId() + "/qualification-checks";
        JsonNode pending = s.body(s.call(get(url), registrar, null).andExpect(status().isOk()));
        assertThat(pending.size()).isEqualTo(2);
        assertThat(pending.findValuesAsText("applicationStatus")).containsOnly("DRAFT");
        assertThat(pending.get(0).get("applicationId").asLong()).isEqualTo(appId);
        assertThat(pending.get(0).at("/qualification/checkStatus").asText()).isEqualTo("PENDING");

        // only the Admissions Office decides; a reason is needed to turn one down; NECTA results are not checked here
        s.call(post("/api/admission/qualifications/" + nva + "/check"), registrar, "{\"verified\":true}").andExpect(status().isForbidden());
        s.call(post("/api/admission/qualifications/" + other + "/check"), officer, "{\"verified\":false}")
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/admission/qualifications/" + csee + "/check"), officer, "{\"verified\":false,\"note\":\"x\"}")
                .andExpect(jsonPath("$.code").value("VERIFIED_AT_SOURCE"));

        s.call(post("/api/admission/qualifications/" + nva + "/check"), officer, "{\"verified\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.qualification.checkStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.qualification.verified").value(true))
                .andExpect(jsonPath("$.qualification.checkedByName").value("Officer " + s.tenant));
        s.call(post("/api/admission/qualifications/" + other + "/check"), officer,
                "{\"verified\":false,\"note\":\"The certificate number does not match VETA's records\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.qualification.checkStatus").value("REJECTED"))
                .andExpect(jsonPath("$.qualification.verified").value(false));

        assertThat(s.body(s.call(get(url), viewer, null)).size()).isZero();
        assertThat(s.body(s.call(get(url + "?status=VERIFIED"), viewer, null)).size()).isEqualTo(1);
        assertThat(s.body(s.call(get(url + "?status=REJECTED"), viewer, null)).at("/0/qualification/checkNote").asText())
                .isEqualTo("The certificate number does not match VETA's records");

        // the applicant sees the outcome and is told by SMS
        JsonNode mine = s.body(s.call(get("/api/applicant/qualifications"), token, null));
        assertThat(mine.findValuesAsText("checkStatus")).containsExactly("VERIFIED", "VERIFIED", "REJECTED");
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=50"), viewer, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms).contains("QUALIFICATION_NOT_ACCEPTED");

        // the application detail carries the same states for the office
        JsonNode detail = s.body(s.call(get("/api/admission/applications/" + appId), officer, null));
        assertThat(detail.get("qualifications").findValuesAsText("checkStatus")).containsExactly("VERIFIED", "VERIFIED", "REJECTED");
    }
}
