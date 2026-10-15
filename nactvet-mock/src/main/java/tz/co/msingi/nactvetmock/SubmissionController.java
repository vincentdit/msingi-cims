package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NACTVET's student registration (FR-REG-07) and results submission (FR-EXM-11) services, NOAS style like admission
 * verification. Each submission is a batch answered with {@code batch_ref}; its outcome is PROCESSING until the processing
 * time has passed. Rules:
 * <pre>
 *   registration: a student without verification_number, date_of_birth or programme_code is QUERIED; otherwise REGISTERED
 *                 (with a NACTVET student number)
 *   results:      a row without verification_number, or with a module without a grade, is QUERIED; the sheet is ACCEPTED
 *                 only if no row is queried
 * </pre>
 * Kept in memory; the paths are placeholders until NACTVET publishes these services.
 */
@RestController
public class SubmissionController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionController.class);
    private static final String BASE = "/nacteapi/index.php/api-out-service";

    record Item(String registrationNo, String status, String reason, String nactvetNumber) {
    }

    record Submission(String batchRef, String kind, String institutionCode, String detail, Instant receivedAt, Instant readyAt, List<Item> items) {
    }

    private final Map<String, Submission> submissions = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final VerificationStore store;
    private final String authorization;
    private final String apiCode;

    public SubmissionController(VerificationStore store, @Value("${mock.authorization}") String authorization, @Value("${mock.api-code}") String apiCode) {
        this.store = store;
        this.authorization = authorization;
        this.apiCode = apiCode;
    }

    @PostMapping(BASE + "/studentregistration")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) JsonNode body) {
        var refused = refuse(body);
        if (refused != null) return refused;
        String inst = text(body, "institution_code");
        if (inst == null || !body.path("students").isArray() || body.path("students").isEmpty()) {
            return answer(422, "institution_code and a non-empty students list are required", null);
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode s : body.path("students")) {
            String why = text(s, "verification_number") == null ? "No admission verification number"
                    : text(s, "date_of_birth") == null || text(s, "programme_code") == null ? "Date of birth or programme code missing" : null;
            items.add(new Item(text(s, "registration_no"), why == null ? "REGISTERED" : "QUERIED", why,
                    why == null ? "NS" + String.format("%07d", seq.incrementAndGet()) : null));
        }
        Submission sub = save("REG", inst, text(body, "academic_year"), items);
        log.info("[NACTVET mock] registration {} from {}: {} student(s)", sub.batchRef(), inst, items.size());
        return answer(200, "Received", Map.of("batch_ref", sub.batchRef(), "received", items.size()));
    }

    @PostMapping(BASE + "/studentregistrationstatus")
    public ResponseEntity<Map<String, Object>> registrationStatus(@RequestBody(required = false) JsonNode body) {
        return status(body, "REG");
    }

    @PostMapping(BASE + "/resultsubmission")
    public ResponseEntity<Map<String, Object>> results(@RequestBody(required = false) JsonNode body) {
        var refused = refuse(body);
        if (refused != null) return refused;
        String inst = text(body, "institution_code");
        if (inst == null || text(body, "programme_code") == null || !body.path("results").isArray() || body.path("results").isEmpty()) {
            return answer(422, "institution_code, programme_code and a non-empty results list are required", null);
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode r : body.path("results")) {
            boolean missingGrade = false;
            for (JsonNode m : r.path("modules")) if (text(m, "grade") == null) missingGrade = true;
            String why = text(r, "verification_number") == null ? "No NACTVET verification number" : missingGrade ? "A module has no grade" : null;
            items.add(new Item(text(r, "registration_no"), why == null ? "ACCEPTED" : "QUERIED", why, null));
        }
        Submission sub = save("RES", inst, text(body, "programme_code") + " " + text(body, "academic_year") + " " + text(body, "semester"), items);
        log.info("[NACTVET mock] results {} from {}: {} student(s)", sub.batchRef(), inst, items.size());
        return answer(200, "Received", Map.of("batch_ref", sub.batchRef(), "received", items.size()));
    }

    @PostMapping(BASE + "/resultsubmissionstatus")
    public ResponseEntity<Map<String, Object>> resultStatus(@RequestBody(required = false) JsonNode body) {
        return status(body, "RES");
    }

    @GetMapping("/mock/submissions")
    public List<Map<String, Object>> list() {
        return submissions.values().stream().sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt())).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batch_ref", s.batchRef());
            m.put("kind", s.kind().equals("REG") ? "Student registration" : "Results");
            m.put("institution_code", s.institutionCode());
            m.put("detail", s.detail());
            m.put("received_at", s.receivedAt().toString());
            m.put("status", state(s));
            m.put("students", s.items().size());
            m.put("queried", s.items().stream().filter(i -> "QUERIED".equals(i.status())).count());
            return m;
        }).toList();
    }

    private Submission save(String kind, String inst, String detail, List<Item> items) {
        Instant now = Instant.now();
        String ref = kind + "-" + inst + "-" + seq.incrementAndGet();
        Submission s = new Submission(ref, kind, inst, detail, now, now.plusSeconds(store.processingSeconds()), items);
        submissions.put(ref, s);
        return s;
    }

    private ResponseEntity<Map<String, Object>> status(JsonNode body, String kind) {
        var refused = refuse(body);
        if (refused != null) return refused;
        String ref = text(body, "batch_ref");
        Submission s = ref == null ? null : submissions.get(ref);
        if (s == null || !s.kind().equals(kind)) return answer(404, "No batch " + ref, null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batch_ref", ref);
        p.put("status", state(s));
        boolean ready = !Instant.now().isBefore(s.readyAt());
        p.put("items", ready ? s.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registration_no", i.registrationNo());
            m.put("status", i.status());
            m.put("reason", i.reason());
            m.put("nactvet_number", i.nactvetNumber());
            return m;
        }).toList() : List.of());
        return answer(200, "Success", p);
    }

    private static String state(Submission s) {
        if (Instant.now().isBefore(s.readyAt())) return "PROCESSING";
        if (s.kind().equals("REG")) return "COMPLETED";
        return s.items().stream().anyMatch(i -> "QUERIED".equals(i.status())) ? "QUERIED" : "ACCEPTED";
    }

    private ResponseEntity<Map<String, Object>> refuse(JsonNode body) {
        if (store.outage()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", 503);
            m.put("message", "Service temporarily unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
        }
        if (body == null || !body.isObject()) return answer(422, "A JSON body is required", null);
        if (!same(text(body, "authorization"), authorization) || !same(text(body, "api_code"), apiCode)) {
            return answer(401, "Invalid authorization or api_code", null);
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> answer(int code, String message, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("params", params);
        return ResponseEntity.ok(m);
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
