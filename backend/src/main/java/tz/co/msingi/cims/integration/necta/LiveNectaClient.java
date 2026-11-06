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

    /** NECTA's API sometimes answers "not found" (or fails) for a candidate it does have, and finds them a moment later. */
    static final int ATTEMPTS = 3;
    /** Candidates found recently: results do not change, so the lookup and the account that follows it ask NECTA once. */
    private static final int CACHE_SIZE = 2000;
    private static final Duration CACHE_FOR = Duration.ofHours(6);

    private final RestClient http;
    private final String resultsPath;
    private final String apiKey;
    private final long retryDelayMs;
    private final Map<String, Cached> found = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    private record Cached(NectaResult result, java.time.Instant until) {
    }

    LiveNectaClient(String baseUrl, String resultsPath, String apiKey, int connectTimeout, int readTimeout) {
        this(baseUrl, resultsPath, apiKey, connectTimeout, readTimeout, 0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LiveNectaClient(@Value("${cims.necta.base-url:https://api.necta.go.tz}") String baseUrl,
                           @Value("${cims.necta.results-path:/api/results/individual}") String resultsPath,
                           @Value("${cims.necta.api-key:}") String apiKey,
                           @Value("${cims.necta.connect-timeout-seconds:10}") int connectTimeout,
                           @Value("${cims.necta.read-timeout-seconds:30}") int readTimeout,
                           @Value("${cims.necta.retry-delay-ms:800}") long retryDelayMs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cims.necta.mode=live needs the NECTA API key: set the NECTA_API_KEY environment variable");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.resultsPath = resultsPath;
        this.apiKey = apiKey;
        this.retryDelayMs = retryDelayMs;
        log.info("NECTA results API: live at {}{}", baseUrl, resultsPath);
    }

    /**
     * The candidate's results: from the recent lookups when found there, otherwise from NECTA, asked up to {@link #ATTEMPTS} times
     * before "not found" or "unavailable" is believed.
     */
    @Override
    public Optional<NectaResult> fetchResults(ExamType exam, String indexNumber, int year) {
        String index = indexNumber.trim().toUpperCase();
        String key = exam + "|" + index + "|" + year;
        Cached c = found.get(key);
        if (c != null && c.until().isAfter(java.time.Instant.now())) return Optional.of(c.result());

        NectaUnavailableException failure = null;
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // a slow NECTA is not asked again: the applicant would wait minutes
            if (attempt > 1 && Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(15)) > 0) break;
            if (attempt > 1) pause(attempt - 1);
            try {
                Optional<NectaResult> r = askOnce(exam, index, year);
                if (r.isPresent()) {
                    if (attempt > 1) log.info("NECTA found {} {} {} on attempt {}", exam, index, year, attempt);
                    found.put(key, new Cached(r.get(), java.time.Instant.now().plus(CACHE_FOR)));
                    return r;
                }
                failure = null;
            } catch (NectaUnavailableException e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        log.info("NECTA has no {} candidate {} in {} ({} attempts)", exam, index, year, ATTEMPTS);
        return Optional.empty();
    }

    private void pause(int n) {
        if (retryDelayMs <= 0) return;
        try {
            Thread.sleep(retryDelayMs * n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<NectaResult> askOnce(ExamType exam, String index, int year) {
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
            log.debug("NECTA answered not found for {} {} {}: {}", exam, index, year, root == null ? "empty body" : root.path("status"));
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
