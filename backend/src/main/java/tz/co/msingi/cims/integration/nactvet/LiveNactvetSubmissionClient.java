package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NACTVET student registration and results by API, in the NOAS style of api-out-service: JSON POSTs with {@code authorization}
 * and {@code api_code}, answered {@code {code, message, params}}. The paths and fields are placeholders until NACTVET publishes
 * these services ({@code cims.nactvet.submission.*}); nactvet-mock answers them. Select with {@code CIMS_NACTVET_SUBMISSION_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.submission", name = "mode", havingValue = "live")
public class LiveNactvetSubmissionClient implements NactvetSubmissionClient {

    private final RestClient http;
    private final String registrationPath;
    private final String registrationStatusPath;
    private final String resultsPath;
    private final String resultsStatusPath;
    private final String authorization;
    private final String apiCode;

    public LiveNactvetSubmissionClient(@Value("${cims.nactvet.submission.base-url}") String baseUrl,
                                       @Value("${cims.nactvet.submission.registration-path}") String registrationPath,
                                       @Value("${cims.nactvet.submission.registration-status-path}") String registrationStatusPath,
                                       @Value("${cims.nactvet.submission.results-path}") String resultsPath,
                                       @Value("${cims.nactvet.submission.results-status-path}") String resultsStatusPath,
                                       @Value("${cims.nactvet.submission.authorization:}") String authorization,
                                       @Value("${cims.nactvet.submission.api-code:}") String apiCode) {
        if (authorization.isBlank() || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.submission.mode=live needs the NACTVET authorization and API code");
        }
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
        f.setReadTimeout(Duration.ofSeconds(90));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.registrationPath = registrationPath;
        this.registrationStatusPath = registrationStatusPath;
        this.resultsPath = resultsPath;
        this.resultsStatusPath = resultsStatusPath;
        this.authorization = authorization;
        this.apiCode = apiCode;
        log.info("NACTVET registration and results: live at {}", baseUrl);
    }

    @Override
    public String submitRegistrations(String institutionCode, String academicYear, List<RegStudent> students) {
        Map<String, Object> body = auth();
        body.put("institution_code", institutionCode);
        body.put("academic_year", academicYear);
        body.put("students", students.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registration_no", s.registrationNo());
            m.put("verification_number", s.verificationNumber());
            m.put("index_number", s.indexNumber());
            m.put("first_name", s.firstName());
            m.put("middle_name", s.middleName());
            m.put("surname", s.surname());
            m.put("sex", s.sex());
            m.put("date_of_birth", s.dateOfBirth());
            m.put("nationality", s.nationality());
            m.put("phone", s.phone());
            m.put("programme_code", s.programmeCode());
            m.put("year_of_study", s.yearOfStudy());
            return m;
        }).toList());
        return batchRef(post(registrationPath, body, "student registration"));
    }

    @Override
    public Status registrationStatus(String batchRef) {
        Map<String, Object> body = auth();
        body.put("batch_ref", batchRef);
        return status(post(registrationStatusPath, body, "registration status " + batchRef).path("params"));
    }

    @Override
    public String submitResults(String institutionCode, Results r) {
        Map<String, Object> body = auth();
        body.put("institution_code", institutionCode);
        body.put("programme_code", r.programmeCode());
        body.put("academic_year", r.academicYear());
        body.put("semester", r.semester());
        body.put("year_of_study", r.yearOfStudy());
        body.put("round", r.round());
        body.put("results", r.rows().stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registration_no", x.registrationNo());
            m.put("verification_number", x.verificationNumber());
            m.put("modules", x.modules().stream().map(g -> Map.of("code", g.code(), "grade", g.grade() == null ? "" : g.grade())).toList());
            m.put("gpa", x.gpa());
            m.put("remark", x.remark());
            return m;
        }).toList());
        return batchRef(post(resultsPath, body, "results submission"));
    }

    @Override
    public Status resultStatus(String batchRef) {
        Map<String, Object> body = auth();
        body.put("batch_ref", batchRef);
        return status(post(resultsStatusPath, body, "results status " + batchRef).path("params"));
    }

    private static Status status(JsonNode p) {
        List<ItemStatus> items = new ArrayList<>();
        for (JsonNode i : p.path("items")) {
            items.add(new ItemStatus(text(i, "registration_no"), text(i, "status"), text(i, "reason"), text(i, "nactvet_number")));
        }
        String state = text(p, "status");
        return new Status(state == null ? "PROCESSING" : state.toUpperCase(), items);
    }

    private static String batchRef(JsonNode root) {
        String ref = text(root.path("params"), "batch_ref");
        if (ref == null) throw new NactvetUnavailableException("NACTVET accepted the submission but returned no batch reference. Please try again.", null);
        return ref;
    }

    private JsonNode post(String path, Map<String, Object> body, String what) {
        JsonNode root;
        try {
            root = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new NactvetUnavailableException("NACTVET could not be reached for the " + what + ". Please try again later.", e);
        }
        int code = root == null ? 0 : root.path("code").asInt(0);
        if (code < 200 || code > 299) {
            String msg = root == null ? "no answer" : root.path("message").asText("refused");
            throw new NactvetUnavailableException("NACTVET did not accept the " + what + " (" + code + " " + msg + ")", null);
        }
        return root;
    }

    private Map<String, Object> auth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorization", authorization);
        m.put("api_code", apiCode);
        return m;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
