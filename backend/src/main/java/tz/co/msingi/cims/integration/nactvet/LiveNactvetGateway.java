package tz.co.msingi.cims.integration.nactvet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * NACTVET admission verification over HTTP (BP-04, FR-ADM-12, FR-INT-02), following NOAS's integration with NACTVET:
 * <ul>
 *   <li><b>Upload</b> ({@code POST /nacteapi/index.php/api/upload}): the body is a JSON array wrapping one
 *       {@code {heading, students}} object. The heading carries {@code authorization}, {@code intake}, {@code programme_id},
 *       {@code application_year}, {@code level} and {@code payment_reference_number}; each student is {@code {particulars}} in
 *       NACTVET's field names (including its spelling {@code impairement} and dates of birth as {@code d-MM-yyyy}).</li>
 *   <li><b>Feedback</b> ({@code POST /nacteapi/index.php/api-out-service/verifiedresultsall}): {@code authorization},
 *       {@code api_code}, {@code page_number}, {@code page_size}, {@code year}, {@code intake}; answered page by page with
 *       {@code code}, {@code total_pages} and {@code params}, one row per applicant with {@code verification_status},
 *       {@code eligibility}, {@code multiple_selection}, {@code student_verification_id} and {@code remarks}.</li>
 * </ul>
 * NACTVET's codes are read as NOAS reads them ({@link NactvetCodes}): 200–202 are success, anything else is a refusal.
 * The multiple-admission confirmation is not part of NOAS; its path stays a placeholder until NACTVET publishes it.
 * Select with {@code CIMS_NACTVET_MODE=live}; the {@code nactvet-mock} project answers the same paths for development.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet", name = "mode", havingValue = "live")
public class LiveNactvetGateway implements NactvetGateway {

    private static final List<DateTimeFormatter> FEEDBACK_DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-M-yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String uploadPath;
    private final String feedbackPath;
    private final String confirmPath;
    private final String authorization;
    private final String apiCode;
    private final int pageSize;
    private final int maxPages;
    private final boolean mockCodes;

    public LiveNactvetGateway(@Value("${cims.nactvet.verification.base-url}") String baseUrl,
                              @Value("${cims.nactvet.verification.upload-path}") String uploadPath,
                              @Value("${cims.nactvet.verification.feedback-path}") String feedbackPath,
                              @Value("${cims.nactvet.verification.confirm-path}") String confirmPath,
                              @Value("${cims.nactvet.verification.authorization:}") String authorization,
                              @Value("${cims.nactvet.verification.api-code:}") String apiCode,
                              @Value("${cims.nactvet.verification.feedback-page-size:500}") int pageSize,
                              @Value("${cims.nactvet.verification.feedback-max-pages:200}") int maxPages,
                              @Value("${cims.nactvet.verification.mock-codes:false}") boolean mockCodes,
                              @Value("${cims.nactvet.verification.connect-timeout-seconds:10}") int connectTimeout,
                              @Value("${cims.nactvet.verification.read-timeout-seconds:60}") int readTimeout) {
        if (authorization == null || authorization.isBlank() || apiCode == null || apiCode.isBlank()) {
            throw new IllegalStateException("cims.nactvet.mode=live needs the NACTVET authorization and API code "
                    + "(CIMS_NACTVET_VERIFICATION_AUTHORIZATION and CIMS_NACTVET_VERIFICATION_API_CODE, or NACTE_AUTHORIZATION and NACTE_API_CODE)");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeout)).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.uploadPath = uploadPath;
        this.feedbackPath = feedbackPath;
        this.confirmPath = confirmPath;
        this.authorization = authorization;
        this.apiCode = apiCode;
        this.pageSize = Math.max(1, Math.min(pageSize, 1000));
        this.maxPages = Math.max(1, maxPages);
        this.mockCodes = mockCodes;
        log.info("NACTVET admission verification: live at {}{}", baseUrl, mockCodes ? " (mock confirmation codes shown in development)" : "");
    }

    // ------------------------------------------------------------------ upload

    @Override
    public String upload(Heading heading, List<Candidate> candidates) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("authorization", authorization);
        h.put("intake", heading.intake());
        h.put("programme_id", heading.programmeId());
        h.put("application_year", heading.applicationYear());
        h.put("level", heading.level());
        h.put("payment_reference_number", heading.paymentReference());
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("heading", h);
        batch.put("students", candidates.stream().map(c -> Map.of("particulars", NactvetTemplate.particulars(c))).toList());
        String what = "upload of " + candidates.size() + " applicant(s) for programme " + heading.programmeId();

        String raw;
        try {
            raw = http.post().uri(uploadPath).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(List.of(batch)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw refused(what, e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.warn("NACTVET verification {} failed: {}", what, e.getMessage());
            throw new NactvetUnavailableException("NACTVET could not be reached. Please try again in a few minutes.", e);
        }
        JsonNode body = parse(raw);
        Integer code = body == null ? null : code(body);
        if (code != null && !NactvetCodes.isSuccess(code)) throw refusal(what, code, text(body, "message", "error", "description"));
        log.info("NACTVET verification: {} accepted", what);
        return raw == null || raw.isBlank() ? "Accepted" : raw.trim();
    }

    // ------------------------------------------------------------------ feedback

    @Override
    public List<Outcome> fetchOutcomes(String applicationYear, String intake, Collection<Candidate> submitted) {
        Matcher matcher = new Matcher(submitted);
        Map<String, Outcome> best = new LinkedHashMap<>();
        int page = 1, pulled = 0, rows = 0, undecided = 0;
        String what = "feedback for " + applicationYear + " " + intake;
        while (pulled < maxPages) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authorization", authorization);
            body.put("api_code", apiCode);
            body.put("page_number", page);
            body.put("page_size", pageSize);
            body.put("year", applicationYear);
            body.put("intake", intake);
            JsonNode root = post(feedbackPath, body, what + " page " + page);
            check(root, what);
            pulled++;
            JsonNode params = root.path("params");
            for (JsonNode r : params) {
                rows++;
                Candidate c = matcher.match(r);
                if (c == null) continue;
                Result result = classify(text(r, "verification_status"), text(r, "eligibility"));
                if (result == null) {
                    undecided++;
                    continue;
                }
                Outcome o = new Outcome(c.applicationRef(), result, reason(r, result),
                        result == Result.VERIFIED ? Optional.ofNullable(text(r, "student_verification_id", "student_verification_code",
                                "verification_id", "verification_code")).orElse(text(r, "user_id")) : null,
                        result == Result.VERIFIED && multiple(text(r, "multiple_selection")));
                best.merge(c.applicationRef(), o, (a, n) -> rank(n.result()) > rank(a.result()) ? n : a);
            }
            int totalPages = root.path("total_pages").asInt(0);
            if (!params.isArray() || params.isEmpty() || page >= Math.max(1, totalPages)) break;
            page++;
        }
        log.info("NACTVET verification: {} feedback row(s) in {} page(s) for {} {}; {} outcome(s) for this batch, {} not decided yet",
                rows, pulled, applicationYear, intake, best.size(), undecided);
        return new ArrayList<>(best.values());
    }

    /**
     * NACTVET's verification status and eligibility, as NOAS reads them: an "approved" status with eligibility "Eligible" is
     * verified; "not approved", "rejected" or "Not Eligible" is not verified; "queried", "returned" or "incomplete" goes back
     * to the institution; anything else (pending, processing, blank) is not decided yet.
     */
    static Result classify(String status, String eligibility) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String e = eligibility == null ? "" : eligibility.trim().toLowerCase(Locale.ROOT);
        if (s.contains("not ") || s.startsWith("not") || s.contains("reject") || s.contains("disapprov") || s.contains("unverif")
                || s.contains("denied") || s.contains("fail")) return Result.REJECTED;
        if (s.contains("quer") || s.contains("return") || s.contains("incomplete") || s.contains("correct")) return Result.QUERIED;
        if (s.contains("approved") || s.contains("verified")) {
            return e.startsWith("not") || e.contains("ineligible") ? Result.REJECTED : Result.VERIFIED;
        }
        return null;
    }

    private static String reason(JsonNode r, Result result) {
        if (result == Result.VERIFIED) return null;
        String remarks = text(r, "remarks");
        if (remarks != null) return remarks.length() > 300 ? remarks.substring(0, 300) : remarks;
        String eligibility = text(r, "eligibility");
        String status = text(r, "verification_status");
        return eligibility != null && eligibility.toLowerCase(Locale.ROOT).startsWith("not") ? eligibility : status;
    }

    private static boolean multiple(String selection) {
        if (blank(selection)) return false;
        String s = selection.trim().toLowerCase(Locale.ROOT);
        return !(s.startsWith("no") || s.equals("0") || s.equals("false") || s.startsWith("single"));
    }

    private static int rank(Result r) {
        return switch (r) {
            case VERIFIED -> 3;
            case REJECTED -> 2;
            case QUERIED -> 1;
        };
    }

    /**
     * Finds the CIMS applicant a feedback row is about: by the CSEE index number in {@code user_id} or
     * {@code student_verification_id} (with or without the exam year), otherwise by first name, surname and date of birth;
     * and only when the row's {@code programme_id} is the programme the applicant was submitted for.
     */
    static final class Matcher {
        private final Map<String, Candidate> byIndex = new HashMap<>();
        private final Map<String, Candidate> byName = new HashMap<>();

        Matcher(Collection<Candidate> candidates) {
            for (Candidate c : candidates) {
                String idx = norm(c.indexNumber());
                if (!idx.isEmpty()) {
                    byIndex.put(idx, c);
                    if (c.examYear() != null) byIndex.put(idx + c.examYear(), c);
                }
                if (c.dateOfBirth() != null && !blank(c.firstName()) && !blank(c.surname())) {
                    byName.put(norm(c.firstName()) + "|" + norm(c.surname()) + "|" + c.dateOfBirth(), c);
                }
            }
        }

        Candidate match(JsonNode r) {
            Candidate c = null;
            for (String k : new String[]{"user_id", "student_verification_id", "form_four_indexnumber", "index_number"}) {
                String v = norm(text(r, k));
                if (!v.isEmpty() && byIndex.containsKey(v)) {
                    c = byIndex.get(v);
                    break;
                }
            }
            if (c == null) {
                LocalDate dob = date(text(r, "date_of_birth", "DOB"));
                if (dob != null) c = byName.get(norm(text(r, "first_name", "firstname")) + "|" + norm(text(r, "last_name", "surname")) + "|" + dob);
            }
            String programme = text(r, "programme_id");
            if (c != null && programme != null && !blank(c.programmeCode()) && !programme.equalsIgnoreCase(c.programmeCode().trim())) {
                return null;   // the same person in another programme
            }
            return c;
        }
    }

    // ------------------------------------------------------------------ confirmation (placeholder path)

    @Override
    public boolean confirmAdmission(String institutionCode, String indexNumber, String confirmationCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorization", authorization);
        body.put("api_code", apiCode);
        body.put("institution_code", institutionCode);
        body.put("index_number", indexNumber);
        body.put("confirmation_code", confirmationCode == null ? null : confirmationCode.trim());
        String what = "confirmation for " + indexNumber;
        JsonNode root = post(confirmPath, body, what);
        Integer code = code(root);
        if (code != null && code == 404) {
            log.warn("NACTVET has no multiple admission for {} at {}", indexNumber, institutionCode);
            return false;
        }
        check(root, what);
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

    // ------------------------------------------------------------------ helpers

    private JsonNode post(String path, Map<String, Object> body, String what) {
        try {
            String raw = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            JsonNode root = parse(raw);
            if (root == null) throw new NactvetUnavailableException("NACTVET returned an empty or unreadable answer. Please try again.", null);
            return root;
        } catch (NactvetUnavailableException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw refused(what, e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.warn("NACTVET verification {} failed: {}", what, e.getMessage());
            throw new NactvetUnavailableException("NACTVET could not be reached. Please try again in a few minutes.", e);
        }
    }

    private static void check(JsonNode root, String what) {
        Integer code = code(root);
        if (code == null || NactvetCodes.isSuccess(code)) return;
        throw refusal(what, code, text(root, "message", "error", "description"));
    }

    /** An HTTP error answer: a credential problem or a refusal when NACTVET says why, otherwise NACTVET is unavailable. */
    private static NactvetUnavailableException refused(String what, int httpStatus, String body, Exception cause) {
        JsonNode n = parse(body);
        Integer code = n == null ? null : code(n);
        int effective = code != null && !NactvetCodes.isSuccess(code) ? code : httpStatus;
        log.warn("NACTVET verification {} failed: HTTP {} {}", what, httpStatus, body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body);
        if (httpStatus >= 500 && (code == null || code >= 500)) {
            return new NactvetUnavailableException("NACTVET could not be reached. Please try again in a few minutes.", cause);
        }
        return refusal(what, effective, n == null ? null : text(n, "message", "error", "description"));
    }

    private static NactvetUnavailableException refusal(String what, int code, String message) {
        log.warn("NACTVET verification {} refused: code {} {}", what, code, message);
        if (NactvetCodes.isCredentialProblem(code)) {
            return new NactvetUnavailableException("NACTVET refused the institution's credentials (code " + code + ": "
                    + NactvetCodes.describe(code) + "). Ask the administrator to check the NACTVET settings.", null);
        }
        return new NactvetUnavailableException("NACTVET refused the request (code " + code + ": " + NactvetCodes.describe(code)
                + (message == null ? "" : ". " + message) + ").", null);
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(raw);
            if (n != null && n.isArray() && n.size() == 1 && n.get(0).isObject()) n = n.get(0);   // some answers come wrapped in an array
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer code(JsonNode root) {
        for (String k : new String[]{"code", "status_code", "statusCode"}) {
            JsonNode v = root.get(k);
            if (v != null && !v.isNull() && v.asText().trim().matches("\\d+")) return Integer.parseInt(v.asText().trim());
        }
        return null;
    }

    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static LocalDate date(String s) {
        if (blank(s)) return null;
        String v = s.trim().length() > 10 && s.trim().charAt(4) == '-' ? s.trim().substring(0, 10) : s.trim();
        for (DateTimeFormatter f : FEEDBACK_DATES) {
            try {
                return LocalDate.parse(v, f);
            } catch (Exception ignored) {
                // next format
            }
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

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
