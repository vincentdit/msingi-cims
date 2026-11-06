package tz.co.msingi.govmocks.nhif;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NHIF's Student Registration API v2, in memory, with the same rules and error codes as CIMS's built-in simulator.
 * <pre>
 * Token        POST /nhif/connect/token (form: client_id, client_secret, grant_type=client_credentials, scope) — mock-agent / mock-secret
 * Endpoints    /nhif/api/v2/... with Authorization: Bearer, SystemId and SubSystemId
 * Registration no date of birth 2070 · no Form Four index 2113 · admission number ending in 7 → 2111 (member already exists)
 * Card appl.   batchNo not id/startYear/endYear/serial 2124 · category 1 without NIDA 2131 · NIDA not registered 3020 ·
 *              category 2 card not starting SN 2139 · duplicate correlation id 3017 · otherwise a control number for TZS 50,400
 * Card check   …0 2137 · …9 INACTIVE · …8 ACTIVE held by NEEMA JOHN KIMARO · other ACTIVE; cards issued here by holder's name
 * </pre>
 * Payments and card issue are made on the dashboard; when the institution's callback is set there, the mock calls CIMS back
 * (token first, then the callback) exactly as NHIF does.
 */
@RestController
public class NhifMockController {

    private static final Logger log = LoggerFactory.getLogger(NhifMockController.class);
    private static final String API = "/nhif/api/v2";
    static final BigDecimal CONTRIBUTION = new BigDecimal("50400.00");

    public static final class Card {
        public String cardNo, status, fullName, scheme, validTo, source;
    }

    public static final class Student {
        public String formFourIndexNo, admissionNo, fullName, nationalId, mobileNo, intakeCode, registeredAt;
    }

    public static final class Application {
        public String batchNo, correlationId, nationalId, cardNo, mobileNo, intakeCode, controlNo, invoiceNo, status, issuedCard, formFourIndexNo;
        public int category;
        public BigDecimal amount = CONTRIBUTION;
    }

    public static final class CallbackSetup {
        public String callbackUrl, tokenUrl, clientId, clientSecret;
    }

    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, Student> students = new ConcurrentHashMap<>();
    private final List<Application> applications = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> checks = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private volatile CallbackSetup callback = new CallbackSetup();

    private final MockSwitches switches;
    private final String clientId;
    private final String clientSecret;
    private final RestClient http = RestClient.create();

    public NhifMockController(MockSwitches switches, @Value("${mock.nhif.client-id:mock-agent}") String clientId,
                              @Value("${mock.nhif.client-secret:mock-secret}") String clientSecret) {
        this.switches = switches;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // --- token ---------------------------------------------------------------------------------------------------------

    @PostMapping(value = "/nhif/connect/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam(name = "client_id", required = false) String id,
                                                     @RequestParam(name = "client_secret", required = false) String secret,
                                                     @RequestParam(name = "grant_type", required = false) String grant,
                                                     @RequestParam(name = "scope", required = false) String scope) {
        if (switches.down("NHIF")) return ResponseEntity.status(503).body(Map.of("error", "NHIF is temporarily unavailable (simulated outage)"));
        if (!"client_credentials".equals(grant)) return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        if (!same(clientId, id) || !same(clientSecret, secret)) return ResponseEntity.status(400).body(Map.of("error", "invalid_client"));
        String t = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        tokens.put(t, Instant.now().plusSeconds(3600));
        return ResponseEntity.ok(Map.of("access_token", t, "token_type", "Bearer", "expires_in", 3600, "scope", scope == null ? "" : scope));
    }

    /** null when the call may go ahead; otherwise NHIF's answer (outage, 401 for the token, 4xxx for the headers). */
    private ResponseEntity<Map<String, Object>> refuse(String authorization, String systemId, String subSystemId, boolean headersRequired) {
        if (switches.down("NHIF")) return ResponseEntity.status(503).body(Map.of("error", "NHIF is temporarily unavailable (simulated outage)"));
        String t = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).trim() : null;
        Instant until = t == null ? null : tokens.get(t);
        if (until == null || until.isBefore(Instant.now())) return ResponseEntity.status(401).build();
        if (headersRequired && (blank(systemId) || blank(subSystemId))) {
            return ResponseEntity.status(400).body(result(0, List.of(failure(0, null, blank(systemId) ? 4001 : 4002,
                    blank(systemId) ? "SystemId" : "SubSystemId", "The request header is missing or invalid.")), 1));
        }
        return null;
    }

    // --- registration --------------------------------------------------------------------------------------------------

    @PostMapping(API + "/student/register")
    public ResponseEntity<Map<String, Object>> registerOne(@RequestHeader(value = "Authorization", required = false) String auth,
                                                           @RequestHeader(value = "SystemId", required = false) String sys,
                                                           @RequestHeader(value = "SubSystemId", required = false) String sub, @RequestBody JsonNode body) {
        var refused = refuse(auth, sys, sub, true);
        if (refused != null) return refused;
        return answer(register(List.of(body)));
    }

    @PostMapping(API + "/students/register")
    public ResponseEntity<Map<String, Object>> registerMany(@RequestHeader(value = "Authorization", required = false) String auth,
                                                            @RequestHeader(value = "SystemId", required = false) String sys,
                                                            @RequestHeader(value = "SubSystemId", required = false) String sub, @RequestBody JsonNode body) {
        var refused = refuse(auth, sys, sub, false);
        if (refused != null) return refused;
        List<JsonNode> list = new ArrayList<>();
        body.forEach(list::add);
        return answer(register(list));
    }

    private Map<String, Object> register(List<JsonNode> list) {
        List<Map<String, Object>> failed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode s = list.get(i);
            String index = text(s, "formFourIndexNo");
            String adm = text(s, "admissionNo");
            String id = index != null ? index : adm;
            if (text(s, "dateOfBirth") == null) failed.add(failure(i, id, 2070, "DateOfBirth", "Invalid date of birth."));
            else if (index == null || !index.matches("[SP]\\d{4}-\\d{4}-\\d{4}")) failed.add(failure(i, id, 2113, "FormFourIndexNo", "Invalid FormFourIndexNo."));
            else if (adm != null && adm.endsWith("7")) {
                failed.add(failure(i, id, 2111, "FirstName", "A member with the same first name, last name, date of birth, and gender already exists."));
            } else {
                Student st = new Student();
                st.formFourIndexNo = index;
                st.admissionNo = adm;
                st.fullName = String.join(" ", nz(text(s, "firstName")), nz(text(s, "middleName")), nz(text(s, "surname"))).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
                st.nationalId = text(s, "nationalId");
                st.mobileNo = text(s, "mobileNo");
                st.intakeCode = text(s, "intakeCode");
                st.registeredAt = Instant.now().toString();
                students.put(index, st);
            }
        }
        log.info("[NHIF mock] registered {} of {} student(s)", list.size() - failed.size(), list.size());
        return result(list.size() - failed.size(), failed, list.size());
    }

    // --- card application ----------------------------------------------------------------------------------------------

    @PostMapping(API + "/student/cardapplication")
    public ResponseEntity<Map<String, Object>> apply(@RequestHeader(value = "Authorization", required = false) String auth,
                                                     @RequestHeader(value = "SystemId", required = false) String sys,
                                                     @RequestHeader(value = "SubSystemId", required = false) String sub, @RequestBody JsonNode body) {
        var refused = refuse(auth, sys, sub, false);
        if (refused != null) return refused;
        String batchNo = text(body, "batchNo");
        List<Map<String, Object>> failed = new ArrayList<>();
        String[] p = batchNo == null ? new String[0] : batchNo.split("/");
        if (batchNo == null || !batchNo.matches("[A-Za-z0-9]+/\\d{4}/\\d{4}/.+") || Integer.parseInt(p[2]) != Integer.parseInt(p[1]) + 1) {
            failed.add(failure(0, batchNo, 2124, "BatchNo", "BatchNo must be in the format id/startYear/endYear/serial (e.g. 5464646/2024/2025/43535-EXC-6464)."));
        } else if (applications.stream().anyMatch(a -> a.batchNo.equals(batchNo))) {
            failed.add(failure(0, batchNo, 3017, "BatchNo", "Duplicate BatchNo."));
        }
        List<JsonNode> details = new ArrayList<>();
        body.path("details").forEach(details::add);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < details.size(); i++) {
            JsonNode d = details.get(i);
            String corr = text(d, "correlationID");
            String nin = text(d, "nationalID");
            int cat = d.path("category").asInt(1);
            String card = text(d, "cardNo");
            if (corr == null || !seen.add(corr)) failed.add(failure(i, corr, 3017, "CorrelationID", "Duplicate CorrelationID found in the request."));
            else if (cat == 1 && nin == null) failed.add(failure(i, corr, 2131, "NationalID", "NationalID is required."));
            else if (cat == 1 && students.values().stream().noneMatch(s -> nin.equals(s.nationalId))) failed.add(failure(i, corr, 3020, "NationalID", "Referenced record was not found."));
            else if (cat == 2 && (card == null || !card.toUpperCase(Locale.ROOT).startsWith("SN"))) failed.add(failure(i, corr, 2139, "CardNo", "The owner of this card is not a student."));
        }
        if (!failed.isEmpty()) return answer(result(0, failed, details.size()));
        List<Application> made = new ArrayList<>();
        for (JsonNode d : details) {
            Application a = new Application();
            a.batchNo = batchNo;
            a.correlationId = text(d, "correlationID");
            a.nationalId = text(d, "nationalID");
            a.cardNo = text(d, "cardNo");
            a.mobileNo = text(d, "mobileNo");
            a.intakeCode = text(d, "intakeCode");
            a.category = d.path("category").asInt(1);
            a.controlNo = String.format("995%09d", Math.floorMod((batchNo + a.correlationId).hashCode(), 1_000_000_000));
            a.invoiceNo = "INV-" + a.controlNo.substring(5);
            a.status = "AWAITING_PAYMENT";
            a.formFourIndexNo = students.values().stream().filter(s -> a.nationalId != null && a.nationalId.equals(s.nationalId)).map(s -> s.formFourIndexNo).findFirst().orElse(null);
            made.add(a);
        }
        applications.addAll(made);
        log.info("[NHIF mock] card application {}: {} student(s)", batchNo, made.size());
        made.forEach(a -> send("card-application", Map.of("batchNo", a.batchNo, "formFourIndexNo", nz(a.formFourIndexNo),
                "identificationNo", a.cardNo != null ? a.cardNo : nz(a.nationalId), "controlNo", a.controlNo, "invoiceAmount", a.amount)));
        return answer(result(made.size(), List.of(), made.size()));
    }

    @GetMapping(API + "/studentcontrolnobybatchno")
    public ResponseEntity<?> controlNumbers(@RequestHeader(value = "Authorization", required = false) String auth, @RequestParam String batchNo) {
        var refused = refuse(auth, null, null, false);
        if (refused != null) return refused;
        return ResponseEntity.ok(applications.stream().filter(a -> a.batchNo.equals(batchNo)).map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("correlationID", a.correlationId);
            m.put("cardNo", a.cardNo);
            m.put("batchNo", a.batchNo);
            m.put("mobileNo", a.mobileNo);
            m.put("invoiceNo", a.invoiceNo);
            m.put("controlNo", a.controlNo);
            m.put("invoiceAmount", a.amount);
            m.put("categoryID", a.category);
            m.put("membershipNo", a.issuedCard);
            return m;
        }).toList());
    }

    // --- card checks, intakes, change requests -------------------------------------------------------------------------

    @GetMapping(API + "/verifystudentcard")
    public ResponseEntity<Map<String, Object>> verify(@RequestHeader(value = "Authorization", required = false) String auth, @RequestParam String cardNo) {
        var refused = refuse(auth, null, null, false);
        if (refused != null) return refused;
        String raw = cardNo.trim();
        Map<String, Object> ok = null;
        Map<String, Object> bad = Map.of("code", 2137, "field", "cardNo", "message", "CardNo does not exist or is not valid.");
        String c = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Card known = cards.get(c);
        if (raw.matches("\\d{20}")) {
            Student s = students.values().stream().filter(x -> raw.equals(x.nationalId)).findFirst().orElse(null);
            if (s != null) ok = card(raw, s.fullName, "ACTIVE", -1, "V" + raw.substring(12));
        } else if (known != null) {
            ok = card(known.cardNo, known.fullName, known.status, "ACTIVE".equals(known.status) ? -1 : 0, known.cardNo + "F3A");
        } else if (!c.isEmpty()) {
            ok = switch (c.charAt(c.length() - 1)) {
                case '0' -> null;
                case '9' -> card(c, null, "INACTIVE", 0, c + "F3A");
                case '8' -> card(c, "NEEMA JOHN KIMARO", "ACTIVE", -1, c + "F3A");
                default -> card(c, null, "ACTIVE", -1, c + "F3A");
            };
        }
        checks.add(0, Map.of("card", raw, "status", ok == null ? "NOT FOUND" : String.valueOf(ok.get("remarks")), "at", Instant.now().toString()));
        if (checks.size() > 50) checks.remove(checks.size() - 1);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("succeeded", ok);
        r.put("failed", ok == null ? bad : null);
        return ResponseEntity.ok(r);
    }

    @GetMapping(API + "/agentintakes")
    public ResponseEntity<?> intakes(@RequestHeader(value = "Authorization", required = false) String auth) {
        var refused = refuse(auth, null, null, false);
        if (refused != null) return refused;
        LocalDate today = LocalDate.now();
        int y = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        return ResponseEntity.ok(List.of(
                Map.of("intakeName", "October " + y + " Intake", "intakeCode", y + "10", "startDate", y + "-10-01", "endDate", y + "-10-31"),
                Map.of("intakeName", "March " + (y + 1) + " Intake", "intakeCode", (y + 1) + "03", "startDate", (y + 1) + "-03-01", "endDate", (y + 1) + "-03-31")));
    }

    @PostMapping(API + "/student/changerequest")
    public ResponseEntity<Map<String, Object>> change(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode body) {
        var refused = refuse(auth, null, null, false);
        if (refused != null) return refused;
        String card = text(body, "cardNo");
        send("change-request", Map.of("cardNo", nz(card), "formFourIndex", nz(text(body, "formFourIndexNo")), "academicYear", nz(text(body, "academicYear")),
                "status", "Approved", "remarks", "Approved by the NHIF mock"));
        return answer(result(1, List.of(), 1));
    }

    // --- developer -----------------------------------------------------------------------------------------------------

    @GetMapping("/mock/nhif")
    public Map<String, Object> state() {
        CallbackSetup c = callback;
        return Map.of("cards", cards.values(), "students", students.values(), "applications", applications, "checks", checks, "callbacks", sent,
                "callback", Map.of("callbackUrl", nz(c.callbackUrl), "tokenUrl", nz(c.tokenUrl), "clientId", nz(c.clientId), "secretSet", c.clientSecret != null),
                "credentials", Map.of("clientId", clientId, "clientSecret", clientSecret, "systemId", 1, "subSystemId", 2));
    }

    /** Where to call the institution back: the callback and token addresses and credentials from CIMS's Integrations › NHIF. */
    @PostMapping("/mock/nhif/callback")
    public ResponseEntity<Void> setCallback(@RequestParam(required = false) String callbackUrl, @RequestParam(required = false) String tokenUrl,
                                            @RequestParam(required = false) String clientId, @RequestParam(required = false) String clientSecret) {
        CallbackSetup c = new CallbackSetup();
        c.callbackUrl = blank(callbackUrl) ? null : callbackUrl.trim();
        c.tokenUrl = blank(tokenUrl) ? null : tokenUrl.trim();
        c.clientId = blank(clientId) ? null : clientId.trim();
        c.clientSecret = blank(clientSecret) ? callback.clientSecret : clientSecret.trim();   // blank keeps the one set
        callback = c;
        return ResponseEntity.noContent().build();
    }

    /** The student paid the control number: the payment callback. */
    @PostMapping("/mock/nhif/pay")
    public ResponseEntity<Application> pay(@RequestParam String controlNo) {
        Application a = find(controlNo);
        if (a == null) return ResponseEntity.notFound().build();
        a.status = "PAID";
        send("payment", Map.of("controlNo", a.controlNo, "status", "Paid", "paymentDate", Instant.now().toString()));
        return ResponseEntity.ok(a);
    }

    /** NHIF issued the card: the card-issued callback (a new SN… card, or the student's own for a renewal). */
    @PostMapping("/mock/nhif/issue")
    public ResponseEntity<Application> issue(@RequestParam String controlNo) {
        Application a = find(controlNo);
        if (a == null) return ResponseEntity.notFound().build();
        a.status = "ISSUED";
        a.issuedCard = a.cardNo != null ? a.cardNo : "SN" + String.format("%09d", Math.floorMod(a.controlNo.hashCode(), 1_000_000_000)).replaceAll("0$", "1");
        Card c = new Card();
        c.cardNo = a.issuedCard;
        c.status = "ACTIVE";
        c.fullName = students.values().stream().filter(s -> a.nationalId != null && a.nationalId.equals(s.nationalId)).map(s -> s.fullName).findFirst().orElse(null);
        c.scheme = "Student";
        LocalDate today = LocalDate.now();
        c.validTo = LocalDate.of(today.getMonthValue() > 6 ? today.getYear() + 1 : today.getYear(), 6, 30).toString();
        c.source = a.batchNo;
        cards.put(c.cardNo, c);
        send("card-issued", Map.of("batchNo", a.batchNo, "formFourIndexNo", nz(a.formFourIndexNo), "identificationNo", a.cardNo != null ? a.cardNo : nz(a.nationalId),
                "controlNo", a.controlNo, "cardno", a.issuedCard));
        return ResponseEntity.ok(a);
    }

    /** Adds a member card, e.g. a student's own card under a parent's scheme. */
    @PostMapping("/mock/nhif/cards")
    public Card add(@RequestParam String cardNo, @RequestParam String fullName, @RequestParam(defaultValue = "ACTIVE") String status,
                    @RequestParam(required = false) String validTo, @RequestParam(defaultValue = "Public servant (dependant)") String scheme) {
        Card c = new Card();
        c.cardNo = cardNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        c.fullName = fullName.trim().toUpperCase(Locale.ROOT);
        c.status = status.trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        c.validTo = validTo != null && !validTo.isBlank() ? validTo : LocalDate.of(today.getMonthValue() > 6 ? today.getYear() + 1 : today.getYear(), 6, 30).toString();
        c.scheme = scheme;
        c.source = "dashboard";
        cards.put(c.cardNo, c);
        return c;
    }

    @PostMapping("/mock/nhif/reset")
    public ResponseEntity<Void> reset() {
        cards.clear();
        students.clear();
        applications.clear();
        sent.clear();
        checks.clear();
        tokens.clear();
        return ResponseEntity.noContent().build();
    }

    // --- calling the institution back ----------------------------------------------------------------------------------

    private void send(String kind, Map<String, Object> payload) {
        CallbackSetup c = callback;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("kind", kind);
        rec.put("payload", payload);
        rec.put("at", Instant.now().toString());
        if (c.callbackUrl == null || c.tokenUrl == null || c.clientId == null || c.clientSecret == null) {
            rec.put("result", "not sent: no callback set");
        } else {
            try {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("client_id", c.clientId);
                form.add("client_secret", c.clientSecret);
                form.add("grant_type", "client_credentials");
                JsonNode t = http.post().uri(c.tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
                String token = t == null ? null : text(t, "access_token");
                JsonNode ack = http.post().uri(c.callbackUrl).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .body(payload).exchange((req, res) -> res.bodyTo(JsonNode.class));
                rec.put("result", ack == null ? "no answer" : ack.path("statusCode").asText() + " " + ack.path("message").asText());
            } catch (RuntimeException e) {
                rec.put("result", "failed: " + e.getMessage());
            }
        }
        log.info("[NHIF mock] {} callback: {}", kind, rec.get("result"));
        sent.add(0, rec);
        if (sent.size() > 100) sent.remove(sent.size() - 1);
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private Application find(String controlNo) {
        return applications.stream().filter(a -> a.controlNo.equals(controlNo.trim())).findFirst().orElse(null);
    }

    private static ResponseEntity<Map<String, Object>> answer(Map<String, Object> r) {
        return Boolean.TRUE.equals(r.get("isFullSuccess")) ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    private static Map<String, Object> result(int succeeded, List<Map<String, Object>> failed, int total) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("succeeded", succeeded);
        r.put("failed", failed);
        r.put("totalRequested", total);
        r.put("hasFailures", !failed.isEmpty());
        r.put("isFullSuccess", failed.isEmpty());
        return r;
    }

    private static Map<String, Object> failure(int index, String identifier, int code, String field, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("index", index);
        f.put("identifier", identifier);
        f.put("errors", List.of(Map.of("code", code, "field", field, "message", message)));
        return f;
    }

    private static Map<String, Object> card(String no, String name, String remarks, int ends, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardNo", no);
        m.put("pfNumber", null);
        m.put("verificationCode", code);
        m.put("fullName", name);
        m.put("remarks", remarks);
        m.put("contributionEnds", ends);
        return m;
    }

    private static boolean same(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
