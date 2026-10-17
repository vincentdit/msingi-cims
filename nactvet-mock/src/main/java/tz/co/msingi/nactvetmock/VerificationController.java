package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.nactvetmock.VerificationStore.Batch;
import tz.co.msingi.nactvetmock.VerificationStore.Candidate;
import tz.co.msingi.nactvetmock.VerificationStore.ConfirmResult;
import tz.co.msingi.nactvetmock.VerificationStore.Heading;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NACTVET's admission verification endpoints, as NOAS uses them:
 * <ul>
 *   <li>{@code POST /nacteapi/index.php/api/upload}: a JSON array wrapping one {@code {heading, students}} object; the heading
 *       carries {@code authorization}, {@code intake}, {@code programme_id}, {@code application_year}, {@code level} and
 *       {@code payment_reference_number}, each student its {@code particulars}.</li>
 *   <li>{@code POST /nacteapi/index.php/api-out-service/verifiedresultsall}: {@code authorization}, {@code api_code},
 *       {@code year}, {@code intake}, {@code page_number}, {@code page_size}; answered with {@code code}, {@code total_pages} and
 *       {@code params}, one row per applicant.</li>
 *   <li>{@code POST /nacteapi/index.php/api-out-service/admissionconfirmation}: not part of NOAS, a placeholder.</li>
 * </ul>
 * Answers are HTTP 200 with a {@code code} (200 OK, 401 wrong credentials, 404 not found, 422 invalid request); during a
 * simulated outage the mock answers HTTP 503. The institution is whoever holds the credential.
 * <p>
 * The {@code /mock/...} endpoints are for the developer only and do not exist at NACTVET.
 */
@RestController
public class VerificationController {

    private static final Logger log = LoggerFactory.getLogger(VerificationController.class);
    private static final String BASE = "/nacteapi/index.php/api-out-service";
    private static final String UPLOAD = "/nacteapi/index.php/api/upload";
    /** The institution: whoever holds the mock's credential (NACTVET knows the institution from its token). */
    static final String INSTITUTION = "MOCK-INSTITUTION";

    private final VerificationStore store;
    private final String authorization;
    private final String apiCode;

    public VerificationController(VerificationStore store, @Value("${mock.authorization}") String authorization,
                                  @Value("${mock.api-code}") String apiCode) {
        this.store = store;
        this.authorization = authorization;
        this.apiCode = apiCode;
    }

    /** Uploads one programme's applicants for verification. */
    @PostMapping(UPLOAD)
    public ResponseEntity<Map<String, Object>> upload(@RequestBody(required = false) JsonNode body) {
        if (store.outage()) return unavailable();
        if (body == null || !body.isArray() || body.size() != 1 || !body.get(0).isObject()) {
            return answer(422, "The body must be a JSON array wrapping one {heading, students} object", null);
        }
        JsonNode h = body.get(0).path("heading");
        if (!same(text(h, "authorization"), authorization)) return answer(401, "Invalid authorization", null);
        List<String> missing = new ArrayList<>();
        for (String k : List.of("intake", "programme_id", "application_year", "level", "payment_reference_number")) {
            if (text(h, k) == null) missing.add(k);
        }
        if (!missing.isEmpty()) return answer(422, "Missing in heading: " + String.join(", ", missing), null);
        String intake = text(h, "intake").toUpperCase();
        if (!intake.equals("SEPTEMBER") && !intake.equals("MARCH")) return answer(422, "intake must be SEPTEMBER or MARCH", null);
        JsonNode students = body.get(0).path("students");
        if (!students.isArray() || students.isEmpty()) return answer(422, "students must be a non-empty list", null);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode st : students) {
            JsonNode p = st.path("particulars");
            candidates.add(new Candidate(text(p, "form_four_indexnumber"), text(p, "form_four_year"), text(p, "firstname"),
                    text(p, "secondname"), text(p, "surname"), text(p, "DOB"), text(p, "gender"), text(p, "impairement"),
                    text(p, "mobile_number")));
        }
        Heading heading = new Heading(intake, text(h, "programme_id"), text(h, "application_year"), text(h, "level"),
                text(h, "payment_reference_number"));
        Batch b = store.upload(INSTITUTION, heading, candidates);
        log.info("[NACTVET mock] {}: {} applicant(s) for programme {} ({} {}, payment {})", b.batchRef(), candidates.size(),
                heading.programmeId(), intake, heading.applicationYear(), heading.paymentReference());
        b.items().stream().filter(i -> i.decision().multipleAdmission()).forEach(i -> log.info(
                "[NACTVET mock] SMS to {}: admitted by more than one institution, confirmation code {}", i.candidate().phone(),
                store.confirmationCode(i.candidate().indexNumber()).orElse("?")));
        return answer(200, "Received " + candidates.size() + " student(s)", Map.of("received", candidates.size(), "reference", b.batchRef()));
    }

    /** Verification feedback for an application year and intake, page by page. */
    @PostMapping(BASE + "/verifiedresultsall")
    public ResponseEntity<Map<String, Object>> feedback(@RequestBody(required = false) JsonNode body) {
        ResponseEntity<Map<String, Object>> refused = refuse(body);
        if (refused != null) return refused;
        String year = text(body, "year");
        String intake = text(body, "intake");
        if (year == null || intake == null) return answer(422, "year and intake are required", null);
        Integer pageNumber = integer(body, "page_number");
        Integer pageSize = integer(body, "page_size");
        int page = pageNumber == null || pageNumber < 1 ? 1 : pageNumber;
        int size = pageSize == null || pageSize < 1 ? 100 : Math.min(pageSize, 1000);
        List<Map<String, Object>> all = store.feedback(INSTITUTION, year, intake);
        int totalPages = Math.max(1, (all.size() + size - 1) / size);
        int from = Math.min(all.size(), (page - 1) * size);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 200);
        m.put("message", "Success");
        m.put("page_number", page);
        m.put("page_size", size);
        m.put("total_records", all.size());
        m.put("total_pages", totalPages);
        m.put("params", all.subList(from, Math.min(all.size(), from + size)));
        return ResponseEntity.ok(m);
    }

    /** An applicant admitted by more than one institution confirms this one. params: {confirmed, reason}. */
    @PostMapping(BASE + "/admissionconfirmation")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody(required = false) JsonNode body) {
        ResponseEntity<Map<String, Object>> refused = refuse(body);
        if (refused != null) return refused;
        String institution = text(body, "institution_code");
        String index = text(body, "index_number");
        if (institution == null || index == null) return answer(422, "institution_code and index_number are required", null);
        ConfirmResult r = store.confirm(INSTITUTION, index, text(body, "confirmation_code"));   // the credential holder
        log.info("[NACTVET mock] confirmation of {} for {}: {}", index, institution, r);
        if (r == ConfirmResult.NOT_ADMITTED) return answer(404, "No multiple admission for " + index + " at " + institution, null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("confirmed", r == ConfirmResult.CONFIRMED ? 1 : 0);
        p.put("reason", r == ConfirmResult.CONFIRMED ? null : r.name());
        return answer(200, r == ConfirmResult.CONFIRMED ? "Confirmed" : "Not confirmed", p);
    }

    // --- developer endpoints -------------------------------------------------------------------------------------------

    @GetMapping("/mock/batches")
    public List<Map<String, Object>> batches() {
        return store.batches().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batch_ref", b.batchRef());
            m.put("institution_code", b.institutionCode());
            m.put("programme_id", b.heading().programmeId());
            m.put("intake", b.heading().intake());
            m.put("application_year", b.heading().applicationYear());
            m.put("payment_reference", b.heading().paymentReference());
            m.put("received_at", b.receivedAt().toString());
            List<Map<String, Object>> outcomes = store.outcomes(b);
            outcomes.forEach(o -> {   // for the developer: the code NACTVET "sent" to a multiple admission
                if (Integer.valueOf(1).equals(o.get("multiple_admission"))) {
                    store.confirmationCode((String) o.get("index_number")).ifPresent(c -> o.put("confirmation_code", c));
                }
            });
            m.put("outcomes", outcomes);
            return m;
        }).toList();
    }

    @GetMapping("/mock/status")
    public Map<String, Object> status() {
        return Map.of("outage", store.outage(), "batches", store.batches().size(), "processing_seconds", store.processingSeconds());
    }

    @GetMapping("/mock/confirmation-codes/{indexNumber}")
    public ResponseEntity<Map<String, Object>> code(@PathVariable String indexNumber) {
        return store.confirmationCode(indexNumber.replace('-', '/'))
                .map(c -> ResponseEntity.ok(Map.<String, Object>of("index_number", indexNumber, "confirmation_code", c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/mock/confirm-elsewhere/{indexNumber}")
    public ResponseEntity<Void> confirmElsewhere(@PathVariable String indexNumber) {
        return store.confirmElsewhere(indexNumber.replace('-', '/')) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/mock/outage")
    public Map<String, Object> outage(@RequestParam boolean on) {
        store.setOutage(on);
        log.info("[NACTVET mock] outage {}", on ? "ON: every NACTVET call answers 503" : "off");
        return Map.of("outage", on);
    }

    @PostMapping("/mock/reset")
    public ResponseEntity<Void> reset() {
        store.reset();
        return ResponseEntity.noContent().build();
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(envelope(503, "Service temporarily unavailable", null));
    }

    private ResponseEntity<Map<String, Object>> refuse(JsonNode body) {
        if (store.outage()) return unavailable();
        if (body == null || !body.isObject()) return answer(422, "A JSON body is required", null);
        if (!same(text(body, "authorization"), authorization) || !same(text(body, "api_code"), apiCode)) {
            return answer(401, "Invalid authorization or api_code", null);
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> answer(int code, String message, Object params) {
        return ResponseEntity.ok(envelope(code, message, params));
    }

    private static Map<String, Object> envelope(int code, String message, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("params", params);
        return m;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static Integer integer(JsonNode n, String key) {
        String s = text(n, key);
        return s != null && s.matches("\\d+") ? Integer.parseInt(s) : null;
    }
}
