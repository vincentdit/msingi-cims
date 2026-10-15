package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"mock.processing-seconds=0", "mock.state-file="})
@AutoConfigureMockMvc
class SubmissionTest {

    static final String BASE = "/nacteapi/index.php/api-out-service";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void studentRegistrationQueriesStudentsWithoutVerification() throws Exception {
        Map<String, Object> body = auth();
        body.put("institution_code", "DEMO");
        body.put("academic_year", "2027/2028");
        body.put("students", List.of(
                Map.of("registration_no", "R1", "verification_number", "NV/2027/000001", "date_of_birth", "2005-01-01", "programme_code", "BTCIT"),
                Map.of("registration_no", "R2", "date_of_birth", "2005-01-01", "programme_code", "BTCIT")));
        String ref = call("/studentregistration", body).path("params").path("batch_ref").asText();
        Map<String, Object> q = auth();
        q.put("batch_ref", ref);
        JsonNode p = call("/studentregistrationstatus", q).path("params");
        assertThat(p.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(p.path("items").get(0).path("status").asText()).isEqualTo("REGISTERED");
        assertThat(p.path("items").get(0).path("nactvet_number").asText()).startsWith("NS");
        assertThat(p.path("items").get(1).path("status").asText()).isEqualTo("QUERIED");
    }

    @Test
    void resultsAreAcceptedOnlyWhenEveryRowIsComplete() throws Exception {
        Map<String, Object> body = auth();
        body.put("institution_code", "DEMO");
        body.put("programme_code", "BTCIT");
        body.put("academic_year", "2027/2028");
        body.put("semester", "Semester 1");
        body.put("results", List.of(Map.of("registration_no", "R1", "verification_number", "NV1", "modules", List.of(Map.of("code", "M1", "grade", "B")))));
        String ref = call("/resultsubmission", body).path("params").path("batch_ref").asText();
        Map<String, Object> q = auth();
        q.put("batch_ref", ref);
        assertThat(call("/resultsubmissionstatus", q).path("params").path("status").asText()).isEqualTo("ACCEPTED");

        body.put("results", List.of(Map.of("registration_no", "R2", "verification_number", "NV2", "modules", List.of(Map.of("code", "M1", "grade", "")))));
        String ref2 = call("/resultsubmission", body).path("params").path("batch_ref").asText();
        q.put("batch_ref", ref2);
        JsonNode p = call("/resultsubmissionstatus", q).path("params");
        assertThat(p.path("status").asText()).isEqualTo("QUERIED");
        assertThat(p.path("items").get(0).path("reason").asText()).contains("no grade");

        Map<String, Object> bad = new LinkedHashMap<>(body);
        bad.put("api_code", "WRONG");
        assertThat(call("/resultsubmission", bad).path("code").asInt()).isEqualTo(401);
    }

    Map<String, Object> auth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorization", "mock-authorization");
        m.put("api_code", "MOCK");
        return m;
    }

    JsonNode call(String path, Object body) throws Exception {
        String s = mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(s);
    }
}
