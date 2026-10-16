package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.admission.service.ApplicationFeeReminders;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-02 application-fee reminders and the FR-ADM-04 entry-rule test tool. */
@IntegrationTest
class FeeRemindersAndEntryTestIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired ApplicationFeeReminders reminders;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, viewer, lecturer;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, new BigDecimal("10000"));
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        viewer = s.staffToken("viewer");
        lecturer = s.staffToken("lecturer");
    }

    @Test
    void applicantsWhoHaveNotPaidAreRemindedOnceAfterTwoDaysAndOnceBeforeClosing() throws Exception {
        String unpaid = startApplication("S0101/0001", false);
        startApplication(nextEligible(), true);                                    // paid: never reminded
        assertThat(s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("feeReminders").asBoolean()).isTrue();

        OffsetDateTime now = OffsetDateTime.now();
        assertThat(run(now)).isEqualTo(new ApplicationFeeReminders.RunResult(0, 0));           // just asked for the control number
        assertThat(run(now.plusDays(3))).isEqualTo(new ApplicationFeeReminders.RunResult(1, 0));
        assertThat(run(now.plusDays(4))).isEqualTo(new ApplicationFeeReminders.RunResult(0, 0)); // once only
        OffsetDateTime closes = OffsetDateTime.parse(s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("closesAt").asText());
        assertThat(run(closes.minusDays(1))).isEqualTo(new ApplicationFeeReminders.RunResult(0, 1));
        assertThat(run(closes.minusHours(2))).isEqualTo(new ApplicationFeeReminders.RunResult(0, 0));
        assertThat(run(closes.plusHours(1))).isEqualTo(new ApplicationFeeReminders.RunResult(0, 0));   // closed

        JsonNode sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content");
        assertThat(sms.findParents("templateCode").stream().filter(m -> m.get("templateCode").asText().equals("APPLICATION_FEE_REMINDER")).count()).isEqualTo(2);
        assertThat(unpaid).isNotBlank();
    }

    @Test
    void theOfficeTriesSampleResultsAndChangedRulesAgainstEveryProgramme() throws Exception {
        String url = "/api/admission/windows/" + inst.windowId() + "/entry-test";
        String four = "{\"qualifications\":[{\"type\":\"CSEE\",\"subjects\":[" + subj("BASIC MATHEMATICS", "C") + "," + subj("ENGLISH", "D") + ","
                + subj("BIOLOGY", "D") + "," + subj("PHYSICS", "C") + "]}]}";
        s.call(post(url), lecturer, four).andExpect(status().isForbidden());
        JsonNode r = s.body(s.call(post(url), officer, four).andExpect(status().isOk()));
        assertThat(r.get("entryLevel").asText()).isEqualTo("NVA Levels 1–3 and NTA Level 4");
        assertThat(r.get("eligibleCount").asInt()).isEqualTo(1);
        assertThat(r.at("/programmes/0/eligible").asBoolean()).isTrue();
        assertThat(r.at("/programmes/0/detail/matchedPathway").asText()).isEqualTo("CSEE");

        String three = "{\"qualifications\":[{\"type\":\"CSEE\",\"subjects\":[" + subj("BASIC MATHEMATICS", "C") + "," + subj("ENGLISH", "D") + ","
                + subj("BIOLOGY", "F") + "," + subj("PHYSICS", "C") + "]}]";
        JsonNode no = s.body(s.call(post(url), officer, three + "}"));
        assertThat(no.at("/programmes/0/eligible").asBoolean()).isFalse();
        assertThat(no.at("/programmes/0/detail/pathways/0/conditions/0/found").asText()).isEqualTo("3 pass(es)");
        // the same results against requirements not yet saved: three passes
        String rules = ",\"windowProgrammeId\":" + inst.windowProgrammeId() + ",\"rules\":{\"summary\":\"Three passes\",\"pathways\":[{\"label\":\"CSEE\","
                + "\"conditions\":[{\"type\":\"CSEE_MIN_PASSES\",\"count\":3,\"minGrade\":\"D\"}]}]}}";
        JsonNode tried = s.body(s.call(post(url), officer, three + rules));
        assertThat(tried.at("/programmes/0/eligible").asBoolean()).isTrue();
        assertThat(tried.at("/programmes/0/triedChangedRules").asBoolean()).isTrue();
        // an NTA Level 5 holder is not at this programme's entry level; bad grades are refused
        JsonNode nta = s.body(s.call(post(url), officer, "{\"qualifications\":[{\"type\":\"NTA\",\"awardLevel\":5}]}"));
        assertThat(nta.get("entryLevel").asText()).isEqualTo("NTA Level 6");
        assertThat(nta.at("/programmes/0/atEntryLevel").asBoolean()).isFalse();
        s.call(post(url), officer, "{\"qualifications\":[{\"type\":\"CSEE\",\"subjects\":[" + subj("ENGLISH", "S") + "]}]}")
                .andExpect(jsonPath("$.code").value("INVALID_GRADE"));
        s.call(post(url), officer, "{\"qualifications\":[{\"type\":\"NVA\"}]}").andExpect(jsonPath("$.code").value("LEVEL_REQUIRED"));
    }

    // ------------------------------------------------------------------ helpers

    private ApplicationFeeReminders.RunResult run(OffsetDateTime at) {
        return TenantContext.callAs(inst.info(), () -> reminders.run(at));
    }

    private int next = 400;

    private String nextEligible() throws Exception {
        for (int i = next; i < next + 2000; i++) {
            String idx = String.format("S0101/%04d", i);
            JsonNode subjects = s.lookup(idx).get("subjects");
            int passes = 0;
            boolean maths = false;
            for (JsonNode sub : subjects) {
                boolean pass = "ABCD".contains(sub.get("grade").asText());
                if (pass) passes++;
                if (pass && sub.get("name").asText().equals("BASIC MATHEMATICS")) maths = true;
            }
            if (passes >= 4 && maths) {
                next = i + 1;
                return idx;
            }
        }
        throw new IllegalStateException("no eligible index");
    }

    /** Signs an applicant up, starts an application and asks for the fee's control number; pays it if asked. */
    private String startApplication(String index, boolean pay) throws Exception {
        String token = s.applicantToken(index);
        s.call(put("/api/applicant/profile"), token, """
                {"firstName":"Test","surname":"Applicant","sex":"F","dateOfBirth":"2006-01-01","nationality":"Tanzanian",
                 "region":"Dodoma","nextOfKinName":"Parent","nextOfKinPhone":"0713000111"}""").andExpect(status().isOk());
        long appId = s.body(s.call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}")).get("id").asLong();
        JsonNode fee = s.body(s.call(post("/api/applicant/applications/" + appId + "/fee"), token, null).andExpect(status().isOk()));
        if (pay) s.call(post("/api/applicant/applications/" + appId + "/fee/simulate-payment"), token, null).andExpect(status().isOk());
        return fee.toString();
    }

    private static String subj(String name, String grade) {
        return "{\"name\":\"" + name + "\",\"grade\":\"" + grade + "\"}";
    }
}
