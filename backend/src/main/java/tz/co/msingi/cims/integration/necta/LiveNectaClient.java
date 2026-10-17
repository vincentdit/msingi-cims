package tz.co.msingi.cims.integration.necta;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The NECTA results API, as used by the National Online Admission System (NOAS): a JSON POST to
 * {@code /api/results/individual} with the index number in dash form ({@code S0101-0001}), the exam year,
 * the numeric exam id (1 = CSEE, 2 = ACSEE) and the authorised API key. NECTA answers with
 * {@code status.code} (positive when found), {@code particulars} (names, sex, centre and the PREM number), {@code results}
 * (division, points) and {@code subjects} (code, name, grade).
 *
 * Active when {@code cims.necta.mode=live}. The API key is never kept in the code: set {@code NECTA_API_KEY}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.necta", name = "mode", havingValue = "live")
public class LiveNectaClient implements NectaClient {

    private final RestClient http;
    private final String resultsPath;
    private final String apiKey;

    public LiveNectaClient(@Value("${cims.necta.base-url:https://api.necta.go.tz}") String baseUrl,
                           @Value("${cims.necta.results-path:/api/results/individual}") String resultsPath,
                           @Value("${cims.necta.api-key:}") String apiKey,
                           @Value("${cims.necta.connect-timeout-seconds:10}") int connectTimeout,
                           @Value("${cims.necta.read-timeout-seconds:30}") int readTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cims.necta.mode=live needs the NECTA API key: set the NECTA_API_KEY environment variable");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.resultsPath = resultsPath;
        this.apiKey = apiKey;
        log.info("NECTA results API: live at {}{}", baseUrl, resultsPath);
    }

    @Override
    public Optional<NectaResult> fetchResults(ExamType exam, String indexNumber, int year) {
        String index = indexNumber.trim().toUpperCase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("index_number", index.replace("/", "-"));
        body.put("exam_year", year);
        body.put("exam_id", String.valueOf(exam.nectaId()));
        body.put("api_key", apiKey);

        JsonNode root;
        try {
            root = http.post().uri(resultsPath).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("NECTA results request failed for {} {} {}: {}", exam, index, year, e.getMessage());
            throw new NectaUnavailableException("NECTA results could not be reached. Please try again in a few minutes.", e);
        }
        if (root == null || root.path("status").path("code").asInt(0) <= 0) {
            log.info("NECTA has no {} candidate {} in {}", exam, index, year);
            return Optional.empty();
        }

        JsonNode p = root.path("particulars");
        String division = null;
        Integer points = null;
        JsonNode results = root.path("results");
        if (results.isObject()) {
            division = text(results.path("division"));
            JsonNode pts = results.path("points");
            if (pts.isNumber() || (pts.isTextual() && pts.asText().matches("\\d+(\\.\\d+)?"))) {
                points = (int) Math.round(pts.asDouble());
            }
        }
        List<SubjectGrade> subjects = new ArrayList<>();
        for (JsonNode s : root.path("subjects")) {
            String grade = text(s.path("grade"));
            if (grade == null) continue;
            subjects.add(new SubjectGrade(text(s.path("subject_code")), upper(s.path("subject_name")), grade.toUpperCase()));
        }
        return Optional.of(new NectaResult(exam, index, year, name(p.path("first_name")), name(p.path("middle_name")),
                name(p.path("last_name")), sex(text(p.path("sex"))), upper(p.path("center_name")), division, points, subjects, prem(root)));
    }

    /**
     * The PREM number: NECTA sends it as {@code particulars.prem_number} (11 digits, e.g. 20140255164); the other spellings are
     * read too in case another NECTA service names it differently.
     */
    static String prem(JsonNode root) {
        for (JsonNode where : new JsonNode[]{root.path("particulars"), root}) {
            for (String k : new String[]{"prem_number", "prem_no", "premNumber", "prem", "PREM", "prem_num"}) {
                String v = text(where.path(k));
                if (v != null) return v.toUpperCase().replaceAll("\\s+", "");
            }
        }
        return null;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String upper(JsonNode n) {
        String s = text(n);
        return s == null ? null : s.toUpperCase();
    }

    /** NECTA sends names in capitals; applicants see them as "Neema", not "NEEMA". */
    static String name(JsonNode n) {
        String s = text(n);
        if (s == null) return null;
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char c : s.toLowerCase().toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString().replaceAll("\\s+", " ");
    }

    private static String sex(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase();
        return v.startsWith("F") ? "F" : v.startsWith("M") ? "M" : null;
    }
}
