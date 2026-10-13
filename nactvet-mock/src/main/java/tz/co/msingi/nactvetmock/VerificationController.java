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

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NACTVET's admission verification endpoints, NOAS style: every call is a JSON POST with {@code authorization} and
 * {@code api_code}; the answer is {@code {code, message, params}} with HTTP 200, and {@code code} says what happened
 * (200 OK, 401 wrong credentials, 404 not found, 422 invalid request). During a simulated outage the mock answers HTTP 503.
 * <p>
 * The {@code /mock/...} endpoints are for the developer only and do not exist at NACTVET.
 */
@RestController
public class VerificationController {

    private static final Logger log = LoggerFactory.getLogger(VerificationController.class);
    private static final String BASE = "/nacteapi/index.php/api-out-service";

    private final VerificationStore store;
    private final String authorization;
    private final String apiCode;

    public VerificationController(VerificationStore store, @Value("${mock.authorization}") String authorization,
                                  @Value("${mock.api-code}") String apiCode) {
        this.store = store;
        this.authorization = authorization;
        this.apiCode = apiCode;
    }

    /** Submits selected applicants for verification. params: {batch_ref, received}. */
    @PostMapping(BASE + "/admissionverification")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody(required = false) JsonNode body) {
        ResponseEntity<Map<String, Object>> refused = refuse(body);
        if (refused != null) return refused;
        String institution = text(body, "institution_code");
        JsonNode list = body.path("candidates");
        if (institution == null) return answer(422, "institution_code is required", null);
        if (!list.isArray() || list.isEmpty()) return answer(422, "candidates must be a non-empty list", null);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode c : list) {
            candidates.add(new Candidate(text(c, "application_ref"), text(c, "index_number"), integer(c, "exam_year"), text(c, "first_name"),
                    text(c, "middle_name"), text(c, "surname"), text(c, "sex"), text(c, "date_of_birth"), text(c, "nationality"),
                    text(c, "phone"), text(c, "programme_code"), text(c, "programme_name"), integer(c, "award_level"), text(c, "remarks")));
        }
        Batch b = store.submit(institution, candidates);
        log.info("[NACTVET mock] {} received {} candidate(s) from {}", b.batchRef(), candidates.size(), institution);
        b.items().stream().filter(i -> i.decision().multipleAdmission()).forEach(i -> log.info(
                "[NACTVET mock] SMS to {}: admitted by more than one institution, confirmation code {}", i.candidate().phone(),
                store.confirmationCode(i.candidate().indexNumber()).orElse("?")));
        return answer(200, "Received", Map.of("batch_ref", b.batchRef(), "received", candidates.size()));
    }

    /** Outcomes for a batch so far. params: one entry per candidate with status PROCESSING, VERIFIED, QUERIED or NOT_VERIFIED. */
    @PostMapping(BASE + "/admissionverificationresults")
    public ResponseEntity<Map<String, Object>> outcomes(@RequestBody(required = false) JsonNode body) {
        ResponseEntity<Map<String, Object>> refused = refuse(body);
        if (refused != null) return refused;
        String ref = text(body, "batch_ref");
        if (ref == null) return answer(422, "batch_ref is required", null);
        return store.batch(ref).map(b -> answer(200, "Success", store.outcomes(b)))
                .orElseGet(() -> answer(404, "No batch " + ref, null));
    }

    /** An applicant admitted by more than one institution confirms this one. params: {confirmed, reason}. */
    @PostMapping(BASE + "/admissionconfirmation")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody(required = false) JsonNode body) {
        ResponseEntity<Map<String, Object>> refused = refuse(body);
        if (refused != null) return refused;
        String institution = text(body, "institution_code");
        String index = text(body, "index_number");
        if (institution == null || index == null) return answer(422, "institution_code and index_number are required", null);
        ConfirmResult r = store.confirm(institution, index, text(body, "confirmation_code"));
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
            m.put("received_at", b.receivedAt().toString());
            m.put("outcomes", store.outcomes(b));
            return m;
        }).toList();
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

    private ResponseEntity<Map<String, Object>> refuse(JsonNode body) {
        if (store.outage()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(envelope(503, "Service temporarily unavailable", null));
        }
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
