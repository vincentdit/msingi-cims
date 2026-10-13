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

@SpringBootTest(properties = "mock.processing-seconds=10")
@AutoConfigureMockMvc
class NactvetMockTest {

    static final String BASE = "/nacteapi/index.php/api-out-service";

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
    void outcomesFollowTheIndexNumberAndAppearOnlyAfterProcessing() throws Exception {
        JsonNode sub = call("/admissionverification", body("DEMO", List.of(
                candidate("APP-1", "S0101/0001", null), candidate("APP-7", "S0101/0007", null), candidate("APP-9", "S0101/0009", null),
                candidate("APP-3", "S0101/0003", null), candidate("APP-5", "S0101/0005", null))));
        assertThat(sub.path("code").asInt()).isEqualTo(200);
        String ref = sub.path("params").path("batch_ref").asText();
        assertThat(ref).startsWith("NV-DEMO-2026-");

        assertThat(statuses(ref)).containsOnly("PROCESSING");

        store.setClock(Clock.fixed(now.plus(Duration.ofSeconds(10)), ZoneOffset.UTC));
        JsonNode p = results(ref);
        assertThat(statuses(ref)).containsExactly("VERIFIED", "QUERIED", "NOT_VERIFIED", "VERIFIED", "PROCESSING");
        assertThat(p.get(0).path("verification_number").asText()).matches("NV/2026/\\d{6}");
        assertThat(p.get(1).path("reason").asText()).contains("does not match NECTA");
        assertThat(p.get(3).path("multiple_admission").asInt()).isEqualTo(1);

        store.setClock(Clock.fixed(now.plus(Duration.ofSeconds(30)), ZoneOffset.UTC));
        assertThat(statuses(ref)).last().isEqualTo("VERIFIED");
    }

    @Test
    void aQueriedCandidateResubmittedWithRemarksIsVerifiedAndDecidedOnesKeepTheirOutcome() throws Exception {
        store.setClock(Clock.fixed(now, ZoneOffset.UTC));
        String first = call("/admissionverification", body("DEMO", List.of(candidate("APP-7", "S0101/0007", null),
                candidate("APP-1", "S0101/0001", null)))).path("params").path("batch_ref").asText();
        store.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        String vno = results(first).get(1).path("verification_number").asText();

        String second = call("/admissionverification", body("DEMO", List.of(candidate("APP-7", "S0101/0007", "Name corrected per NECTA"),
                candidate("APP-1", "S0101/0001", null)))).path("params").path("batch_ref").asText();
        store.setClock(Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC));
        JsonNode p = results(second);
        assertThat(p.get(0).path("status").asText()).isEqualTo("VERIFIED");
        assertThat(p.get(1).path("verification_number").asText()).isEqualTo(vno);
    }

    @Test
    void incompleteAndDuplicateCandidatesAreQueried() throws Exception {
        Map<String, Object> noBirth = candidate("APP-2", "S0101/0002", null);
        noBirth.remove("date_of_birth");
        String ref = call("/admissionverification", body("DEMO", List.of(noBirth, candidate("APP-4", "S0101/0004", null),
                candidate("APP-4B", "S0101/0004", null)))).path("params").path("batch_ref").asText();
        store.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        JsonNode p = results(ref);
        assertThat(p.get(0).path("reason").asText()).isEqualTo("Missing date of birth in the candidate's record");
        assertThat(p.get(1).path("status").asText()).isEqualTo("VERIFIED");
        assertThat(p.get(2).path("reason").asText()).contains("more than once");
    }

    @Test
    void confirmationNeedsTheRightCodeAndOnlyOneInstitutionCanHaveIt() throws Exception {
        call("/admissionverification", body("DEMO", List.of(candidate("APP-3", "S0101/0003", null))));
        String code = store.confirmationCode("S0101/0003").orElseThrow();

        JsonNode wrong = call("/admissionconfirmation", confirm("DEMO", "S0101/0003", "000000".equals(code) ? "111111" : "000000"));
        assertThat(wrong.path("params").path("confirmed").asInt()).isZero();
        assertThat(wrong.path("params").path("reason").asText()).isEqualTo("WRONG_CODE");

        assertThat(call("/admissionconfirmation", confirm("ELSE", "S0101/0003", code)).path("code").asInt()).isEqualTo(404);
        assertThat(call("/admissionconfirmation", confirm("DEMO", "S0101/0003", code)).path("params").path("confirmed").asInt()).isEqualTo(1);

        store.confirmElsewhere("S0101/0003");
        assertThat(call("/admissionconfirmation", confirm("DEMO", "S0101/0003", code)).path("params").path("reason").asText())
                .isEqualTo("ALREADY_CONFIRMED_ELSEWHERE");
    }

    @Test
    void wrongCredentialsUnknownBatchesAndOutagesAreRefused() throws Exception {
        Map<String, Object> bad = body("DEMO", List.of(candidate("APP-1", "S0101/0001", null)));
        bad.put("api_code", "WRONG");
        assertThat(call("/admissionverification", bad).path("code").asInt()).isEqualTo(401);

        Map<String, Object> q = auth();
        q.put("batch_ref", "NV-NOPE");
        assertThat(call("/admissionverificationresults", q).path("code").asInt()).isEqualTo(404);
        assertThat(call("/admissionverification", body("DEMO", List.of())).path("code").asInt()).isEqualTo(422);

        store.setOutage(true);
        mvc.perform(post(BASE + "/admissionverificationresults").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(q)))
                .andExpect(status().isServiceUnavailable());
    }

    // --- helpers ---

    private JsonNode call(String path, Map<String, Object> body) throws Exception {
        String out = mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    private JsonNode results(String ref) throws Exception {
        Map<String, Object> q = auth();
        q.put("batch_ref", ref);
        return call("/admissionverificationresults", q).path("params");
    }

    private List<String> statuses(String ref) throws Exception {
        List<String> s = new ArrayList<>();
        results(ref).forEach(n -> s.add(n.path("status").asText()));
        return s;
    }

    private static Map<String, Object> auth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorization", "mock-authorization");
        m.put("api_code", "MOCK");
        return m;
    }

    private static Map<String, Object> body(String institution, List<Map<String, Object>> candidates) {
        Map<String, Object> m = auth();
        m.put("institution_code", institution);
        m.put("candidates", candidates);
        return m;
    }

    private static Map<String, Object> confirm(String institution, String index, String code) {
        Map<String, Object> m = auth();
        m.put("institution_code", institution);
        m.put("index_number", index);
        m.put("confirmation_code", code);
        return m;
    }

    private static Map<String, Object> candidate(String ref, String index, String remarks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application_ref", ref);
        m.put("index_number", index);
        m.put("exam_year", 2024);
        m.put("first_name", "Asha");
        m.put("surname", "Mussa");
        m.put("sex", "F");
        m.put("date_of_birth", "2006-03-14");
        m.put("phone", "255754000111");
        m.put("programme_code", "NTA6-ODIT");
        m.put("award_level", 6);
        m.put("remarks", remarks);
        return m;
    }
}
