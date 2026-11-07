package tz.co.msingi.govmocks.sms;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two Tanzanian SMS providers in one, as CIMS's adapters call them: NextSMS API v2 (under /nextsms: /api/sms/v2/text/single and its
 * test twin, /api/v2/reports, /api/v2/balance; Bearer API token) and Beem (under /beem: /v1/send, /public/v1/vendors/balance; reports
 * under /beem-dlr; Basic authentication). Only the sender names listed as registered are accepted. A message is delivered a few
 * seconds after it is sent (mock.sms.deliver-seconds), except to a number ending in 000 (handset unreachable); the report can be
 * asked for, and is posted to the delivery-report address set on the dashboard (the address the Msingi console shows).
 */
@RestController
public class SmsMockController {

    private static final Logger log = LoggerFactory.getLogger(SmsMockController.class);

    static final String NEXT_TOKEN = "mock-nextsms-token", NEXT_VERIFY = "mock-verify-token";
    static final String BEEM_KEY = "mock-beem-key", BEEM_SECRET = "mock-beem-secret";

    public static final class Sms {
        public String provider, id, from, to, text, reference, status = "PENDING", createdAt = Instant.now().toString(), result;
        public Long recipientId;
        public boolean test;
    }

    private final Map<String, Sms> messages = new ConcurrentHashMap<>();
    private final List<Sms> order = new CopyOnWriteArrayList<>();
    private final Set<String> senders = ConcurrentHashMap.newKeySet();
    private final Map<String, String> callbacks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(778800);
    private final AtomicLong balance = new AtomicLong(5000);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final RestClient http = RestClient.create();
    private final MockSwitches switches;
    private final int deliverSeconds;

    public SmsMockController(MockSwitches switches, @Value("${mock.sms.deliver-seconds:4}") int deliverSeconds) {
        this.switches = switches;
        this.deliverSeconds = deliverSeconds;
        senders.addAll(List.of("MSINGI", "BAHARI", "BIHAS", "DEMO"));
    }

    // ================================================================== NextSMS

    @PostMapping({"/nextsms/api/sms/v2/text/single", "/nextsms/api/sms/v2/test/text/single"})
    public ResponseEntity<Map<String, Object>> nextSend(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode body,
                                                        jakarta.servlet.http.HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> no = refuseBearer(auth);
        if (no != null) return no;
        String from = text(body, "from"), to = text(body, "to"), txt = text(body, "text");
        if (to == null || txt == null) return ResponseEntity.badRequest().body(Map.of("message", "to and text are required"));
        Sms m = new Sms();
        m.provider = "NEXTSMS";
        m.id = String.valueOf(seq.incrementAndGet()) + "1631440";
        m.from = from;
        m.to = to;
        m.text = txt;
        m.reference = text(body, "reference");
        m.test = req.getRequestURI().contains("/test/");
        Map<String, Object> st = new LinkedHashMap<>();
        if (from == null || !senders.contains(from)) {
            m.status = "REJECTED";
            st.put("groupId", 5);
            st.put("groupName", "REJECTED");
            st.put("id", 56);
            st.put("name", "REJECTED_SOURCE");
            st.put("description", "Sender ID in the request not registered on your account.");
        } else {
            st.put("groupId", 18);
            st.put("groupName", "PENDING");
            st.put("id", 51);
            st.put("name", "ENROUTE (SENT)");
            st.put("description", "Message sent to next instance");
            accept(m);
        }
        remember(m);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("to", to);
        one.put("status", st);
        if (!"REJECTED".equals(m.status)) one.put("messageId", m.id);   // NextSMS gives no id to a refused sender name
        one.put("smsCount", 1);
        one.put("message", txt);
        return ResponseEntity.ok(Map.of("messages", List.of(one), "sendReference", m.reference == null ? "" : m.reference));
    }

    @GetMapping("/nextsms/api/v2/reports")
    public ResponseEntity<Map<String, Object>> nextReports(@RequestHeader(value = "Authorization", required = false) String auth,
                                                           @RequestParam(required = false) String messageId) {
        ResponseEntity<Map<String, Object>> no = refuseBearer(auth);
        if (no != null) return no;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Sms m : order) {
            if (!"NEXTSMS".equals(m.provider) || messageId != null && !messageId.equals(m.id)) continue;
            results.add(nextReport(m));
        }
        return ResponseEntity.ok(Map.of("results", results));
    }

    @GetMapping("/nextsms/api/v2/balance")
    public ResponseEntity<Map<String, Object>> nextBalance(@RequestHeader(value = "Authorization", required = false) String auth) {
        ResponseEntity<Map<String, Object>> no = refuseBearer(auth);
        return no != null ? no : ResponseEntity.ok(Map.of("sms_balance", balance.get(), "over_draft", 0, "type", "count", "default", "sms",
                "display", balance.get() + " SMS"));
    }

    // ================================================================== Beem

    @PostMapping("/beem/v1/send")
    public ResponseEntity<Map<String, Object>> beemSend(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode body) {
        ResponseEntity<Map<String, Object>> no = refuse(auth, BEEM_KEY, BEEM_SECRET);
        if (no != null) return no;
        String from = text(body, "source_addr");
        if (from == null || !senders.contains(from)) {
            return ResponseEntity.ok(Map.of("successful", false, "code", 111, "message", "Invalid Sender Id"));
        }
        long requestId = seq.incrementAndGet();
        int valid = 0;
        for (JsonNode r : body.path("recipients")) {
            Sms m = new Sms();
            m.provider = "BEEM";
            m.id = String.valueOf(requestId);
            m.from = from;
            m.to = text(r, "dest_addr");
            m.recipientId = r.path("recipient_id").asLong();
            m.text = text(body, "message");
            remember(m);
            accept(m);
            valid++;
        }
        return ResponseEntity.ok(Map.of("successful", true, "request_id", requestId, "code", 100, "message", "Message Submitted Successfully",
                "valid", valid, "invalid", 0, "duplicates", 0));
    }

    @GetMapping("/beem-dlr/public/v1/delivery-reports")
    public ResponseEntity<?> beemReports(@RequestHeader(value = "Authorization", required = false) String auth, @RequestParam("dest_addr") String dest,
                                         @RequestParam("request_id") String requestId) {
        ResponseEntity<Map<String, Object>> no = refuse(auth, BEEM_KEY, BEEM_SECRET);
        if (no != null) return no;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Sms m : order) {
            if ("BEEM".equals(m.provider) && m.id.equals(requestId) && dest.equals(m.to)) out.add(beemReport(m));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/beem/public/v1/vendors/balance")
    public ResponseEntity<Map<String, Object>> beemBalance(@RequestHeader(value = "Authorization", required = false) String auth) {
        ResponseEntity<Map<String, Object>> no = refuse(auth, BEEM_KEY, BEEM_SECRET);
        return no != null ? no : ResponseEntity.ok(Map.of("data", Map.of("credit_balance", balance.get())));
    }

    // ================================================================== delivery

    private void accept(Sms m) {
        if (!m.test) balance.decrementAndGet();
        timer.schedule(() -> deliver(m), deliverSeconds, TimeUnit.SECONDS);
    }

    private void deliver(Sms m) {
        m.status = m.to != null && m.to.endsWith("000") ? "UNDELIVERED" : "DELIVERED";
        String url = callbacks.get(m.provider);
        if (url == null) {
            m.result = "no delivery-report address set (CIMS can still ask)";
            return;
        }
        try {
            // NextSMS's webhook: token (the Verify Token set with the address), messageId, status, id
            Object body = "NEXTSMS".equals(m.provider)
                    ? Map.of("token", NEXT_VERIFY, "messageId", m.id, "status", "DELIVERED".equals(m.status) ? "DELIVERED" : "UNDELIVERABLE", "id", 1)
                    : beemReport(m);
            m.result = http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((req, res) -> res.getStatusCode().value() + " " + new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            m.result = "failed: " + e.getMessage();
        }
        log.info("[sms mock] {} {} {}: {}", m.provider, m.id, m.status, m.result);
    }

    private static Map<String, Object> nextReport(Sms m) {
        Map<String, Object> st = new LinkedHashMap<>();
        switch (m.status) {
            case "DELIVERED" -> {
                st.put("groupName", "DELIVERED");
                st.put("name", "DELIVERED_TO_HANDSET");
                st.put("description", "Message delivered to handset");
            }
            case "UNDELIVERED" -> {
                st.put("groupName", "UNDELIVERABLE");
                st.put("name", "UNDELIVERABLE_NOT_DELIVERED");
                st.put("description", "Handset unreachable");
            }
            case "REJECTED" -> {
                st.put("groupName", "REJECTED");
                st.put("name", "REJECTED_SENDER");
                st.put("description", "Sender name not registered");
            }
            default -> {
                st.put("groupName", "PENDING");
                st.put("name", "PENDING_ENROUTE");
                st.put("description", "Message sent to next instance");
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("messageId", m.id);
        r.put("to", m.to);
        r.put("sentAt", m.createdAt);
        r.put("smsCount", 1);
        r.put("status", st);
        return r;
    }

    private static Map<String, Object> beemReport(Sms m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", m.status);
        r.put("dest_addr", m.to);
        r.put("request_id", m.id);
        r.put("recipient_id", m.recipientId == null ? null : String.valueOf(m.recipientId));
        return r;
    }

    // ================================================================== developer

    @GetMapping("/mock/sms")
    public Map<String, Object> state() {
        List<Sms> recent = new ArrayList<>(order);
        java.util.Collections.reverse(recent);
        return Map.of("messages", recent, "senders", senders, "callbacks", callbacks, "balance", balance.get(), "deliverSeconds", deliverSeconds,
                "accounts", Map.of(
                        "NEXTSMS", Map.of("apiToken", NEXT_TOKEN, "baseUrl", "http://localhost:8098/nextsms"),
                        "BEEM", Map.of("apiKey", BEEM_KEY, "secretKey", BEEM_SECRET, "baseUrl", "http://localhost:8098/beem",
                                "reportUrl", "http://localhost:8098/beem-dlr")));
    }

    /** A sender name the provider has "registered" with TCRA and the networks (or takes back). */
    @PostMapping("/mock/sms/senders")
    public ResponseEntity<Void> sender(@RequestParam String name, @RequestParam(defaultValue = "true") boolean registered) {
        if (registered) senders.add(name.trim());
        else senders.remove(name.trim());
        return ResponseEntity.noContent().build();
    }

    /** Where the provider posts delivery reports (the address the Msingi console shows on its SMS page). */
    @PostMapping("/mock/sms/callback")
    public ResponseEntity<Void> setCallback(@RequestParam String provider, @RequestParam(required = false) String url) {
        if (url == null || url.isBlank()) callbacks.remove(provider.toUpperCase());
        else callbacks.put(provider.toUpperCase(), url.trim());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mock/sms/reset")
    public ResponseEntity<Void> reset() {
        messages.clear();
        order.clear();
        balance.set(5000);
        return ResponseEntity.noContent().build();
    }

    // ================================================================== helpers

    private void remember(Sms m) {
        messages.put(m.provider + ":" + m.id + ":" + m.to, m);
        order.add(m);
        if (order.size() > 300) order.remove(0);
    }

    private ResponseEntity<Map<String, Object>> refuseBearer(String auth) {
        if (switches.down("SMS")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "SMS provider temporarily unavailable (simulated outage)"));
        }
        String expected = "Bearer " + NEXT_TOKEN;
        if (auth == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), auth.trim().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthenticated."));
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> refuse(String auth, String user, String password) {
        if (switches.down("SMS")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "SMS provider temporarily unavailable (simulated outage)"));
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        if (auth == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), auth.trim().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid credentials"));
        }
        return null;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
