package tz.co.msingi.cims.admission.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BP-03: propose → committee review (exclusion) → approval by a different person → capacity carried to the next round. */
@IntegrationTest
class SelectionIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired WindowProgrammeRepository windowProgrammes;
    @Autowired TransactionTemplate tx;

    String tenant;
    TestData.Institution inst;
    int phoneSeq = 0;

    @BeforeEach
    void setUp() {
        tenant = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO); // free window keeps the test focused on selection
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(s ->
                windowProgrammes.findById(inst.windowProgrammeId()).orElseThrow().setCapacity(2)));
    }

    @Test
    void fullSelectionWorkflow() throws Exception {
        // three eligible applicants; the strongest has the maximum CSEE score (index …/0001)
        List<Long> apps = new ArrayList<>();
        apps.add(apply("S0101/0001"));
        apps.add(applyEligible(1000));
        apps.add(applyEligible(apps.size() * 1000 + 3000));

        String officer = staffToken("officer");
        String chair = staffToken("chair");

        // officer runs the proposal: 2 places for 3 applicants
        JsonNode round = body(call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), officer, null)
                .andExpect(status().isOk()));
        long roundId = round.at("/round/id").asLong();
        assertThat(round.at("/totals/selected").asInt()).isEqualTo(2);
        assertThat(round.at("/totals/notSelected").asInt()).isEqualTo(1);
        JsonNode selected = round.at("/programmes/0/selected");
        assertThat(selected.get(0).get("applicationId").asLong()).isEqualTo(apps.get(0)); // best merit ranked first
        assertThat(selected.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(round.get("canApprove").asBoolean()).isFalse(); // proposer cannot approve

        // committee excludes the top applicant with a reason → the waiting applicant moves up
        long waiting = round.at("/notSelected/0/applicationId").asLong();
        round = body(call(post("/api/admission/selection-rounds/" + roundId + "/exclusions"), officer,
                "{\"applicationId\":" + apps.get(0) + ",\"reason\":\"Certificate under investigation\"}").andExpect(status().isOk()));
        assertThat(round.at("/totals/excluded").asInt()).isEqualTo(1);
        assertThat(round.at("/totals/selected").asInt()).isEqualTo(2);
        assertThat(round.at("/programmes/0/selected").findValuesAsText("applicationId")).contains(String.valueOf(waiting));

        // and reinstates them again
        round = body(call(delete("/api/admission/selection-rounds/" + roundId + "/exclusions/" + apps.get(0)), officer, null));
        assertThat(round.at("/totals/excluded").asInt()).isZero();

        // segregation of duties: the officer (who also holds INSTITUTION_ADMIN) cannot approve their own proposal
        call(post("/api/admission/selection-rounds/" + roundId + "/approve"), officer, "{\"committeeMinuteRef\":\"AC/2027/04\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        // the committee chair approves
        call(post("/api/admission/selection-rounds/" + roundId + "/approve"), chair,
                "{\"committeeMinuteRef\":\"AC/2027/04\",\"notes\":\"Approved as presented\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round.status").value("APPROVED"))
                .andExpect(jsonPath("$.round.approvedByName").value("Staff chair"));

        call(get("/api/admission/applications?status=SELECTED"), officer, null).andExpect(jsonPath("$.totalElements").value(2));

        // round 2: no places left, so nobody is selected and the waiting applicant stays SUBMITTED
        JsonNode round2 = body(call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), officer, null));
        assertThat(round2.at("/round/roundNo").asInt()).isEqualTo(2);
        assertThat(round2.at("/totals/selected").asInt()).isZero();
        assertThat(round2.at("/programmes/0/availableThisRound").asInt()).isZero();

        // CSV export for the committee pack
        String csv = call(get("/api/admission/selection-rounds/" + roundId + "/export"), officer, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Programme,Rank,Reference").contains("Selected");
    }

    @Test
    void applicantSeesSelectedProgramme() throws Exception {
        String token = applicantToken("S0101/0001");
        Long appId = finish(token);
        String officer = staffToken("officer");
        long roundId = body(call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), officer, null)).at("/round/id").asLong();
        call(post("/api/admission/selection-rounds/" + roundId + "/approve"), staffToken("chair"), "{\"committeeMinuteRef\":\"AC/1\"}")
                .andExpect(status().isOk());
        call(get("/api/applicant/applications/" + appId), token, null)
                .andExpect(jsonPath("$.status").value("SELECTED"))
                .andExpect(jsonPath("$.selectedProgramme").value("Basic Technician Certificate in IT (" + tenant + ")"));
    }

    @Test
    void cannotRunWithoutSubmittedApplications() throws Exception {
        call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), staffToken("officer"), null)
                .andExpect(jsonPath("$.code").value("NO_CANDIDATES"));
    }

    // ------------------------------------------------------------------ helpers

    private Long apply(String index) throws Exception {
        return finish(applicantToken(index));
    }

    /** Applies with simulated NECTA results, trying index numbers until one meets the window's rules. */
    private Long applyEligible(int start) throws Exception {
        for (int i = start; i < start + 200; i++) {
            String index = String.format("S0303/%04d", i);
            JsonNode c = body(call(post("/api/applicant-auth/necta-lookup"), null, "{\"indexNumber\":\"" + index + "\",\"examYear\":2025}"));
            int passes = 0;
            boolean maths = false;
            for (JsonNode sub : c.get("subjects")) {
                boolean pass = "ABCD".contains(sub.get("grade").asText());
                if (pass) passes++;
                if (pass && sub.get("name").asText().equals("BASIC MATHEMATICS")) maths = true;
            }
            if (passes >= 4 && maths) {
                return apply(index);
            }
        }
        throw new AssertionError("no eligible simulated index found");
    }

    private Long finish(String token) throws Exception {
        call(put("/api/applicant/profile"), token, """
                {"firstName":"Test","surname":"Applicant","sex":"F","dateOfBirth":"2006-01-01","nationality":"Tanzanian",
                 "region":"Dodoma","nextOfKinName":"Parent","nextOfKinPhone":"0713000111"}""").andExpect(status().isOk());
        long appId = body(call(post("/api/applicant/applications"), token, "{\"windowId\":" + inst.windowId() + "}")).get("id").asLong();
        call(put("/api/applicant/applications/" + appId + "/choices"), token, "{\"windowProgrammeIds\":[" + inst.windowProgrammeId() + "]}")
                .andExpect(status().isOk());
        call(post("/api/applicant/applications/" + appId + "/submit"), token, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        return appId;
    }

    private String applicantToken(String index) throws Exception {
        String phone = String.format("0744%06d", ++phoneSeq + (Math.abs(tenant.hashCode()) % 9000) * 100);
        JsonNode reg = body(call(post("/api/applicant-auth/register"), null,
                "{\"indexNumber\":\"" + index + "\",\"examYear\":2025,\"phone\":\"" + phone + "\",\"password\":\"Secret@123\",\"acceptPrivacyNotice\":true}")
                .andExpect(status().isCreated()));
        return body(call(post("/api/applicant-auth/verify"), null,
                "{\"phone\":\"" + phone + "\",\"code\":\"" + reg.get("devOtp").asText() + "\"}")).get("accessToken").asText();
    }

    private String staffToken(String username) throws Exception {
        return body(call(post("/api/auth/login"), null,
                "{\"username\":\"" + username + "\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}").andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("X-Tenant", tenant);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
