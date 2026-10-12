package tz.co.msingi.cims.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drives the admission API for one test institution: applicants, applications, selection. */
public class AdmissionScenario {

    private final MockMvc mvc;
    private final ObjectMapper json;
    public final String tenant;
    public final TestData.Institution inst;
    private int phoneSeq;

    public AdmissionScenario(MockMvc mvc, ObjectMapper json, String tenant, TestData.Institution inst) {
        this.mvc = mvc;
        this.json = json;
        this.tenant = tenant;
        this.inst = inst;
    }

    /** A submitted application by an applicant registered with the given CSEE index number (2025). */
    public Applicant apply(String index) throws Exception {
        String token = applicantToken(index);
        return new Applicant(finish(token), token, index);
    }

    /**
     * A submitted, eligible application whose simulated index number ends in {@code lastDigit}
     * (the simulated NACTVET decides its outcome by that digit). Candidates are looked up at (simulated)
     * NECTA first, and the first one meeting the test window's rules (four passes including Basic Mathematics) registers.
     */
    public Applicant applyEligibleEndingIn(int lastDigit, int start) throws Exception {
        for (int i = start - start % 10 + lastDigit; i < start + 3000; i += 10) {
            String index = String.format("S0404/%04d", i);
            if (meetsTestRules(index)) {
                Applicant a = apply(index);
                return a;
            }
        }
        throw new AssertionError("no eligible simulated index ending in " + lastDigit);
    }

    /** What (simulated) NECTA holds for a 2025 CSEE index number. */
    public JsonNode lookup(String index) throws Exception {
        return body(call(post("/api/applicant-auth/necta-lookup"), null, "{\"indexNumber\":\"" + index + "\",\"examYear\":2025}")
                .andExpect(status().isOk()));
    }

    private boolean meetsTestRules(String index) throws Exception {
        int passes = 0;
        boolean maths = false;
        for (JsonNode sub : lookup(index).get("subjects")) {
            boolean pass = "ABCD".contains(sub.get("grade").asText());
            if (pass) passes++;
            if (pass && sub.get("name").asText().equals("BASIC MATHEMATICS")) maths = true;
        }
        return passes >= 4 && maths;
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

    /** Runs a selection round as {@code proposer} and has {@code approver} approve it; returns the round detail. */
    public JsonNode selectAndApprove(String proposerToken, String approverToken) throws Exception {
        JsonNode round = body(call(post("/api/admission/windows/" + inst.windowId() + "/selection-rounds"), proposerToken, null)
                .andExpect(status().isOk()));
        long roundId = round.at("/round/id").asLong();
        return body(call(post("/api/admission/selection-rounds/" + roundId + "/approve"), approverToken,
                "{\"committeeMinuteRef\":\"AC/TEST/1\"}").andExpect(status().isOk()));
    }

    /** Selection approved, verified by (simulated) NACTVET and offer accepted, for the given applicants. */
    public void admit(String officerToken, String chairToken, Applicant... applicants) throws Exception {
        selectAndApprove(officerToken, chairToken);
        long batch = body(call(post("/api/admission/windows/" + inst.windowId() + "/nactvet-submissions"), officerToken,
                "{\"channel\":\"API\"}").andExpect(status().isOk())).get("id").asLong();
        call(post("/api/admission/nactvet-submissions/" + batch + "/fetch-outcomes"), officerToken, null).andExpect(status().isOk());
        for (Applicant a : applicants) {
            call(post("/api/applicant/applications/" + a.applicationId() + "/offer/accept"), a.token(), "{}")
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }
    }

    /** A verified applicant registered with a made-up index number (any results). */
    public String applicantToken() throws Exception {
        return applicantToken(String.format("S0909/%04d", phoneSeq + 1));
    }

    /** A verified applicant registered with the given CSEE index number (2025); names come from (simulated) NECTA. */
    public String applicantToken(String index) throws Exception {
        String phone = String.format("0755%06d", ++phoneSeq + (Math.abs(tenant.hashCode()) % 9000) * 100);
        JsonNode reg = body(call(post("/api/applicant-auth/register"), null,
                "{\"indexNumber\":\"" + index + "\",\"examYear\":2025,\"phone\":\"" + phone + "\",\"password\":\"Secret123\",\"acceptPrivacyNotice\":true}")
                .andExpect(status().isCreated()));
        return body(call(post("/api/applicant-auth/verify"), null,
                "{\"phone\":\"" + phone + "\",\"code\":\"" + reg.get("devOtp").asText() + "\"}")).get("accessToken").asText();
    }

    public String staffToken(String username) throws Exception {
        return body(call(post("/api/auth/login"), null,
                "{\"username\":\"" + username + "\",\"password\":\"" + TestData.STAFF_PASSWORD + "\"}").andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    public ResultActions call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        req.header("X-Tenant", tenant);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    public JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String text(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public record Applicant(Long applicationId, String token, String index) {
    }
}
