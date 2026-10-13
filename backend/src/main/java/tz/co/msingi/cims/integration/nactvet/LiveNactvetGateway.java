package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NACTVET admission verification over HTTP (BP-04, FR-ADM-12, FR-INT-02), in the NOAS style of NACTVET's api-out-service:
 * a JSON POST carrying {@code authorization} and {@code api_code}, answered with {@code {code, message, params}} where a
 * non-2xx {@code code} is an error.
 * <ul>
 *   <li><b>Submit</b>: {@code institution_code} and {@code candidates} (NACTVET's verification template); returns {@code batch_ref}.</li>
 *   <li><b>Results</b>: {@code batch_ref}; one entry per candidate with {@code status} PROCESSING, VERIFIED, QUERIED or NOT_VERIFIED.
 *       Candidates still PROCESSING are left out, so the admissions office fetches again later.</li>
 *   <li><b>Confirmation</b>: {@code institution_code}, {@code index_number} and {@code confirmation_code}; returns {@code confirmed}.</li>
 * </ul>
 * The paths and field names are placeholders until NACTVET publishes its admission verification API; they are configuration
 * ({@code cims.nactvet.verification.*}). The {@code nactvet-mock} project answers them for development. Select with
 * {@code CIMS_NACTVET_MODE=live}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet", name = "mode", havingValue = "live")
public class LiveNactvetGateway implements NactvetGateway {

    private static final Set<Integer> OK = Set.of(200, 201, 202);

    private final RestClient http;
    private final String submitPath;
    private final String resultsPath;
    private final String confirmPath;
    private final String authorization;
    private final String apiCode;
    private final boolean mockCodes;

    public LiveNactvetGateway(@Value("${cims.nactvet.verification.base-url}") String baseUrl,
                              @Value("${cims.nactvet.verification.submit-path}") String submitPath,
                              @Value("${cims.nactvet.verification.results-path}") String resultsPath,
                              @Value("${cims.nactvet.verification.confirm-path}") String confirmPath,
                              @Value("${cims.nactvet.verification.authorization:}") String authorization,
                              @Value("${cims.nactvet.verification.api-code:}") String apiCode,
                              @Value("${cims.nactvet.verification.mock-codes:false}") boolean mockCodes,
                              @Value("${cims.nactvet.verification.connect-timeout-seconds:8}") int connectTimeout,
                              @Value("${cims.nactvet.verification.read-timeout-seconds:45}") int readTimeout) {
        if (authorization == null || authorization.isBlank() || apiCode == null || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.mode=live needs the NACTVET authorization and API code "
                    + "(CIMS_NACTVET_VERIFICATION_AUTHORIZATION and CIMS_NACTVET_VERIFICATION_API_CODE, or NACTE_AUTHORIZATION and NACTE_API_CODE)");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeout)).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.submitPath = submitPath;
        this.resultsPath = resultsPath;
        this.confirmPath = confirmPath;
        this.authorization = authorization;
        this.apiCode = apiCode;
        this.mockCodes = mockCodes;
        log.info("NACTVET admission verification: live at {}{}", baseUrl, mockCodes ? " (mock confirmation codes shown in development)" : "");
    }

    @Override
    public String submit(String institutionCode, List<Candidate> candidates) {
        Map<String, Object> body = auth();
        body.put("institution_code", institutionCode);
        body.put("candidates", candidates.stream().map(LiveNactvetGateway::template).toList());
        JsonNode params = call(submitPath, body, "submit " + candidates.size() + " candidate(s)").path("params");
        String ref = text(params, "batch_ref", "batch_reference", "reference");
        if (ref == null) throw new NactvetUnavailableException("NACTVET accepted the submission but returned no batch reference. Please try again.", null);
        log.info("NACTVET verification: batch {} with {} candidate(s)", ref, candidates.size());
        return ref;
    }

    @Override
    public List<Outcome> fetchOutcomes(String externalRef, Collection<Candidate> submitted) {
        Map<String, Object> body = auth();
        body.put("batch_ref", externalRef);
        JsonNode params = call(resultsPath, body, "results of " + externalRef).path("params");
        Set<String> waiting = submitted.stream().map(Candidate::applicationRef).collect(Collectors.toSet());
        List<Outcome> out = new ArrayList<>();
        int processing = 0;
        for (JsonNode r : params) {
            String ref = text(r, "application_ref");
            String status = Optional.ofNullable(text(r, "status")).orElse("").toUpperCase();
            if (ref == null || !waiting.contains(ref)) continue;
            Result result = switch (status) {
                case "VERIFIED" -> Result.VERIFIED;
                case "QUERIED" -> Result.QUERIED;
                case "NOT_VERIFIED", "REJECTED" -> Result.REJECTED;
                default -> null;   // PROCESSING, or a status this adapter does not know: ask again later
            };
            if (result == null) {
                processing++;
                continue;
            }
            out.add(new Outcome(ref, result, text(r, "reason"), text(r, "verification_number", "verification_no"), flag(r.get("multiple_admission"))));
        }
        log.info("NACTVET verification: {} outcome(s) for {}, {} still processing", out.size(), externalRef, processing);
        return out;
    }

    @Override
    public boolean confirmAdmission(String institutionCode, String indexNumber, String confirmationCode) {
        Map<String, Object> body = auth();
        body.put("institution_code", institutionCode);
        body.put("index_number", indexNumber);
        body.put("confirmation_code", confirmationCode == null ? null : confirmationCode.trim());
        JsonNode root = post(confirmPath, body, "confirmation for " + indexNumber);
        Integer code = code(root);
        if (code != null && code == 404) {
            log.warn("NACTVET has no multiple admission for {} at {}", indexNumber, institutionCode);
            return false;
        }
        check(root, "confirmation for " + indexNumber);
        JsonNode p = root.path("params");
        if (flag(p.get("confirmed"))) return true;
        if ("ALREADY_CONFIRMED_ELSEWHERE".equalsIgnoreCase(text(p, "reason"))) {
            throw new BusinessRuleException("CONFIRMED_ELSEWHERE",
                    "NACTVET records that you have already confirmed admission at another institution. Contact NACTVET or the admissions office.");
        }
        return false;
    }

    /** Development only, and only against nactvet-mock: the code it would have sent by SMS, so it can be shown on screen. */
    @Override
    public Optional<String> developmentConfirmationCode(String indexNumber) {
        if (!mockCodes || indexNumber == null) return Optional.empty();
        try {
            JsonNode n = http.get().uri("/mock/confirmation-codes/{index}", indexNumber.replace('/', '-')).retrieve().body(JsonNode.class);
            return Optional.ofNullable(n == null ? null : text(n, "confirmation_code"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private JsonNode call(String path, Map<String, Object> body, String what) {
        JsonNode root = post(path, body, what);
        check(root, what);
        return root;
    }

    private JsonNode post(String path, Map<String, Object> body, String what) {
        try {
            JsonNode root = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            if (root == null) throw new NactvetUnavailableException("NACTVET returned an empty answer. Please try again.", null);
            return root;
        } catch (NactvetUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("NACTVET verification {} failed: {}", what, e.getMessage());
            throw new NactvetUnavailableException("NACTVET could not be reached. Please try again in a few minutes.", e);
        }
    }

    private static void check(JsonNode root, String what) {
        Integer code = code(root);
        if (code == null || OK.contains(code)) return;
        String message = text(root, "message");
        log.warn("NACTVET verification {} refused: code {} {}", what, code, message);
        if (code == 401 || code == 403) {
            throw new NactvetUnavailableException("NACTVET refused the institution's credentials (code " + code
                    + "). Ask the administrator to check the NACTVET settings.", null);
        }
        throw new NactvetUnavailableException("NACTVET refused the request (code " + code + (message == null ? "" : ": " + message)
                + "). Please try again later.", null);
    }

    private Map<String, Object> auth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authorization", authorization);
        m.put("api_code", apiCode);
        return m;
    }

    /** NACTVET's admission verification template: one row per selected applicant. */
    static Map<String, Object> template(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application_ref", c.applicationRef());
        m.put("index_number", c.indexNumber());
        m.put("exam_year", c.examYear());
        m.put("first_name", c.firstName());
        m.put("middle_name", c.middleName());
        m.put("surname", c.surname());
        m.put("sex", c.sex());
        m.put("date_of_birth", c.dateOfBirth() == null ? null : c.dateOfBirth().toString());
        m.put("nationality", c.nationality());
        m.put("phone", c.phone());
        m.put("programme_code", c.programmeCode());
        m.put("programme_name", c.programmeName());
        m.put("award_level", c.awardLevel());
        m.put("remarks", c.remarks());
        return m;
    }

    private static Integer code(JsonNode root) {
        for (String k : new String[]{"code", "status_code", "statusCode"}) {
            JsonNode v = root.get(k);
            if (v != null && !v.isNull() && v.asText().trim().matches("\\d+")) return Integer.parseInt(v.asText().trim());
        }
        return null;
    }

    private static boolean flag(JsonNode v) {
        if (v == null || v.isNull()) return false;
        String s = v.asText().trim();
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }
}
