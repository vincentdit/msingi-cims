package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mock.processing-seconds=10", "mock.state-file="})
@AutoConfigureMockMvc
class NactvetMockTest {

    static final String BASE = "/nacteapi/index.php/api-out-service";
    static final String UPLOAD = "/nacteapi/index.php/api/upload";

    @Autowired MockMvc mvc;
    @Autowired VerificationStore store;
    @Autowired ObjectMapper json;

    Instant now = Instant.parse("2026-10-13T08:00:00Z");

    @BeforeEach
    void reset() {
        store.reset();
        store.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void outcomesFollowTheIndexNumberAndAppearInTheFeedbackOnlyAfterProcessing() throws Exception {
        JsonNode up = upload("4501", List.of(student("S0101/0001"), student("S0101/0007"), student("S0101/0009"),
                student("S0101/0003"), student("S0101/0005")));
        assertThat(up.path("code").asInt()).isEqualTo(200);
        assertThat(up.path("params").path("received").asInt()).isEqualTo(5);

        assertThat(statuses()).containsOnly("Pending");

        store.setClock(Clock.fixed(now.plus(Duration.ofSeconds(10)), ZoneOffset.UTC));
        JsonNode p = feedback(1, 100).path("params");
        assertThat(statuses()).containsExactly("Verified and Approved", "Returned for correction", "Not Approved",
                "Verified and Approved", "Pending");
        assertThat(p.get(0).path("student_verification_id").asText()).matches("NV/2026/\\d{6}");
        assertThat(p.get(0).path("user_id").asText()).isEqualTo("S0101/0001");
        assertThat(p.get(0).path("date_of_birth").asText()).isEqualTo("2006-03-04");
        assertThat(p.get(0).path("eligibility").asText()).isEqualTo("Eligible");
        assertThat(p.get(1).path("remarks").asText()).contains("does not match NECTA");
        assertThat(p.get(2).path("eligibility").asText()).isEqualTo("Not Eligible");
        assertThat(p.get(3).path("multiple_selection").asText()).isEqualTo("Multiple selection");

        store.setClock(Clock.fixed(now.plus(Duration.ofSeconds(30)), ZoneOffset.UTC));
        assertThat(statuses()).last().isEqualTo("Verified and Approved");
    }

    @Test
    void feedbackIsPagedAndKeptPerYearAndIntake() throws Exception {
        upload("4501", List.of(student("S0101/0001"), student("S0101/0002"), student("S0101/0004")));
        JsonNode first = feedback(1, 2);
        assertThat(first.path("total_records").asInt()).isEqualTo(3);
        assertThat(first.path("total_pages").asInt()).isEqualTo(2);
        assertThat(first.path("params")).hasSize(2);
        assertThat(feedback(2, 2).path("params")).hasSize(1);

        Map<String, Object> other = feedbackBody(1, 10);
        other.put("intake", "MARCH");
        assertThat(call(BASE + "/verifiedresultsall", other).path("params")).isEmpty();
    }

    @Test
    void aQueriedApplicantUploadedAgainIsVerifiedAndDecidedOnesKeepTheirOutcome() throws Exception {
        upload("4501", List.of(student("S0101/0007"), student("S0101/0001")));
        store.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        String vno = feedback(1, 10).path("params").get(1).path("student_verification_id").asText();

        upload("4501", List.of(student("S0101/0007"), student("S0101/0001")));
        store.setClock(Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC));
        JsonNode p = feedback(1, 10).path("params");
        assertThat(p).hasSize(2);   // the latest upload of each applicant
        assertThat(p.get(0).path("verification_status").asText()).isEqualTo("Verified and Approved");
        assertThat(p.get(1).path("student_verification_id").asText()).isEqualTo(vno);
    }

    @Test
    void incompleteAndDuplicateApplicantsAreQueried() throws Exception {
        Map<String, Object> noBirth = student("S0101/0002");
        noBirth.put("DOB", "");
        upload("4501", List.of(noBirth, student("S0101/0004"), student("S0101/0004")));
        store.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        JsonNode p = feedback(1, 10).path("params");
        assertThat(p.get(0).path("remarks").asText()).isEqualTo("Missing date of birth in the applicant's particulars");
        assertThat(p.get(1).path("remarks").asText()).contains("more than once");   // the later row for the same index wins
    }

    @Test
    void confirmationNeedsTheRightCodeAndOnlyOneInstitutionCanHaveIt() throws Exception {
        upload("4501", List.of(student("S0101/0003")));
        String code = store.confirmationCode("S0101/0003").orElseThrow();

        JsonNode wrong = call(BASE + "/admissionconfirmation", confirm("DEMO", "S0101/0003", "000000".equals(code) ? "111111" : "000000"));
        assertThat(wrong.path("params").path("confirmed").asInt()).isZero();
        assertThat(wrong.path("params").path("reason").asText()).isEqualTo("WRONG_CODE");

        assertThat(call(BASE + "/admissionconfirmation", confirm("DEMO", "S0101/0099", code)).path("code").asInt()).isEqualTo(404);
        assertThat(call(BASE + "/admissionconfirmation", confirm("DEMO", "S0101/0003", code)).path("params").path("confirmed").asInt()).isEqualTo(1);

        store.confirmElsewhere("S0101/0003");
        assertThat(call(BASE + "/admissionconfirmation", confirm("DEMO", "S0101/0003", code)).path("params").path("reason").asText())
                .isEqualTo("ALREADY_CONFIRMED_ELSEWHERE");
    }

    @Test
    void wrongCredentialsIncompleteHeadingsAndOutagesAreRefused() throws Exception {
        List<Map<String, Object>> bad = uploadBody("4501", List.of(student("S0101/0001")));
        heading(bad).put("authorization", "WRONG");
        assertThat(call(UPLOAD, bad).path("code").asInt()).isEqualTo(401);

        List<Map<String, Object>> noPayment = uploadBody("4501", List.of(student("S0101/0001")));
        heading(noPayment).remove("payment_reference_number");
        assertThat(call(UPLOAD, noPayment).path("message").asText()).contains("payment_reference_number");
        assertThat(call(UPLOAD, uploadBody("4501", List.of())).path("code").asInt()).isEqualTo(422);
        assertThat(call(UPLOAD, Map.of("heading", Map.of())).path("code").asInt()).isEqualTo(422);   // not wrapped in an array

        Map<String, Object> q = feedbackBody(1, 10);
        q.put("api_code", "WRONG");
        assertThat(call(BASE + "/verifiedresultsall", q).path("code").asInt()).isEqualTo(401);

        store.setOutage(true);
        mvc.perform(post(BASE + "/verifiedresultsall").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(feedbackBody(1, 10))))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post(UPLOAD).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(uploadBody("4501", List.of(student("S0101/0001"))))))
                .andExpect(status().isServiceUnavailable());
    }

    // --- helpers ---

    private JsonNode call(String path, Object body) throws Exception {
        String out = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    private JsonNode upload(String programmeId, List<Map<String, Object>> students) throws Exception {
        return call(UPLOAD, uploadBody(programmeId, students));
    }

    private JsonNode feedback(int page, int size) throws Exception {
        return call(BASE + "/verifiedresultsall", feedbackBody(page, size));
    }

    private List<String> statuses() throws Exception {
        List<String> s = new ArrayList<>();
        feedback(1, 100).path("params").forEach(n -> s.add(n.path("verification_status").asText()));
        return s;
    }

    private static Map<String, Object> auth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorization", "mock-authorization");
        m.put("api_code", "MOCK");
        return m;
    }

    private static Map<String, Object> feedbackBody(int page, int size) {
        Map<String, Object> m = auth();
        m.put("page_number", page);
        m.put("page_size", size);
        m.put("year", "2026");
        m.put("intake", "SEPTEMBER");
        return m;
    }

    private static List<Map<String, Object>> uploadBody(String programmeId, List<Map<String, Object>> students) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("authorization", "mock-authorization");
        h.put("intake", "SEPTEMBER");
        h.put("programme_id", programmeId);
        h.put("application_year", "2026");
        h.put("level", "6");
        h.put("payment_reference_number", "991100223344");
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("heading", h);
        batch.put("students", students.stream().map(st -> Map.of("particulars", st)).toList());
        return List.of(batch);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> heading(List<Map<String, Object>> body) {
        return (Map<String, Object>) body.get(0).get("heading");
    }

    private static Map<String, Object> confirm(String institution, String index, String code) {
        Map<String, Object> m = auth();
        m.put("institution_code", institution);
        m.put("index_number", index);
        m.put("confirmation_code", code);
        return m;
    }

    private static Map<String, Object> student(String index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstname", "Asha");
        m.put("secondname", "Juma");
        m.put("surname", "Mussa");
        m.put("DOB", "4-03-2006");
        m.put("gender", "Female");
        m.put("impairement", "None");
        m.put("form_four_indexnumber", index);
        m.put("form_four_year", "2024");
        m.put("mobile_number", "0754000111");
        return m;
    }
}
