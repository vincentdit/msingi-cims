package tz.co.msingi.govmocks.aggregators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tz.co.msingi.govmocks.common.MockSwitches;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Three Tanzanian payment aggregators in one, as CIMS's adapters call them: Selcom (signed checkout API under /selcom/v1), AzamPay
 * (authenticator under /azampay-auth, checkout under /azampay) and ClickPesa (/clickpesa/third-parties). A phone prompt is "paid"
 * a few seconds after it is sent (mock.aggregators.complete-seconds), except to a phone ending in 9 ("insufficient balance");
 * the aggregator then notifies the institution as it would: Selcom at the order's webhook (signed), AzamPay and ClickPesa at the
 * callback address set on the dashboard.
 */
@RestController
public class AggregatorMockController {

    private static final Logger log = LoggerFactory.getLogger(AggregatorMockController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final class Order {
        public String provider, orderId, phone, network, status = "PENDING", transId, webhook, reference, message;
        public BigDecimal amount;
        public String createdAt = Instant.now().toString();
    }

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, String> callbacks = new ConcurrentHashMap<>();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final RestClient http = RestClient.create();
    private final MockSwitches switches;
    private final int completeSeconds;

    // the merchant credentials each aggregator issued to the test institution
    static final String SELCOM_VENDOR = "TILL60001", SELCOM_KEY = "mock-selcom-key", SELCOM_SECRET = "mock-selcom-secret";
    static final String AZAM_APP = "cims", AZAM_CLIENT = "mock-azam-client", AZAM_SECRET = "mock-azam-secret", AZAM_KEY = "mock-azam-key";
    static final String CLICK_CLIENT = "mock-clickpesa-client", CLICK_KEY = "mock-clickpesa-key";

    public AggregatorMockController(MockSwitches switches, @Value("${mock.aggregators.complete-seconds:5}") int completeSeconds) {
        this.switches = switches;
        this.completeSeconds = completeSeconds;
    }

    // ================================================================== Selcom

    @PostMapping("/selcom/v1/checkout/create-order-minimal")
    public ResponseEntity<Map<String, Object>> selcomOrder(@RequestHeader Map<String, String> h, @RequestBody JsonNode b) {
        ResponseEntity<Map<String, Object>> refused = selcomRefuse(h, b);
        if (refused != null) return refused;
        if (!SELCOM_VENDOR.equals(text(b, "vendor"))) return ResponseEntity.ok(selcom("403", "FAIL", "Unknown vendor"));
        Order o = new Order();
        o.provider = "SELCOM";
        o.orderId = text(b, "order_id");
        o.phone = text(b, "buyer_phone");
        o.amount = new BigDecimal(text(b, "amount"));
        o.reference = text(b, "merchant_remarks");
        String wh = text(b, "webhook");
        o.webhook = wh == null ? null : new String(Base64.getDecoder().decode(wh), StandardCharsets.UTF_8);
        orders.put(key(o.provider, o.orderId), o);
        Map<String, Object> r = selcom("000", "SUCCESS", "Order created successfully");
        r.put("data", List.of(Map.of("payment_token", String.valueOf(80000000 + Math.abs(o.orderId.hashCode() % 10000000)))));
        return ResponseEntity.ok(r);
    }

    @PostMapping("/selcom/v1/checkout/wallet-payment")
    public ResponseEntity<Map<String, Object>> selcomPush(@RequestHeader Map<String, String> h, @RequestBody JsonNode b) {
        ResponseEntity<Map<String, Object>> refused = selcomRefuse(h, b);
        if (refused != null) return refused;
        Order o = orders.get(key("SELCOM", text(b, "order_id")));
        if (o == null) return ResponseEntity.ok(selcom("404", "FAIL", "Order not found"));
        o.phone = text(b, "msisdn");
        schedule(o);
        return ResponseEntity.ok(selcom("111", "PENDING", "Request in progress. You will receive a callback shortly"));
    }

    @GetMapping("/selcom/v1/checkout/order-status")
    public ResponseEntity<Map<String, Object>> selcomStatus(@RequestHeader Map<String, String> h, @RequestParam("order_id") String orderId) {
        ResponseEntity<Map<String, Object>> refused = selcomRefuse(h, JSON.createObjectNode().put("order_id", orderId));
        if (refused != null) return refused;
        Order o = orders.get(key("SELCOM", orderId));
        if (o == null) return ResponseEntity.ok(selcom("404", "FAIL", "Order not found"));
        Map<String, Object> r = selcom("000", "SUCCESS", "Order fetch successful");
        r.put("data", List.of(selcomData(o)));
        return ResponseEntity.ok(r);
    }

    private Map<String, Object> selcomData(Order o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("order_id", o.orderId);
        d.put("creation_date", o.createdAt);
        d.put("amount", o.amount.toPlainString());
        d.put("payment_status", switch (o.status) { case "PAID" -> "COMPLETED"; case "FAILED" -> "REJECTED"; default -> "PENDING"; });
        d.put("transid", o.transId);
        d.put("channel", o.network == null ? "MPESA-TZ" : o.network);
        d.put("reference", o.transId == null ? null : "SEL" + o.transId);
        d.put("phone", o.phone);
        return d;
    }

    private ResponseEntity<Map<String, Object>> selcomRefuse(Map<String, String> headers, JsonNode body) {
        if (switches.down("AGGREGATORS")) return ResponseEntity.status(503).body(Map.of("message", "Selcom is temporarily unavailable (simulated outage)"));
        Map<String, String> h = lower(headers);
        String auth = h.get("authorization");
        if (auth == null || !auth.equals("SELCOM " + Base64.getEncoder().encodeToString(SELCOM_KEY.getBytes(StandardCharsets.UTF_8)))) {
            return ResponseEntity.status(401).body(selcom("401", "FAIL", "Invalid API key"));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        String signed = h.getOrDefault("signed-fields", "");
        for (String f : signed.split(",")) if (!f.isBlank()) fields.put(f.trim(), text(body, f.trim()) == null ? "" : text(body, f.trim()));
        String expected = digest(SELCOM_SECRET, h.getOrDefault("timestamp", ""), fields);
        if (!expected.equals(h.get("digest"))) return ResponseEntity.status(401).body(selcom("401", "FAIL", "Invalid digest"));
        return null;
    }

    private static Map<String, Object> selcom(String code, String result, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("reference", "SR" + System.nanoTime() % 1_000_000_000L);
        r.put("resultcode", code);
        r.put("result", result);
        r.put("message", message);
        return r;
    }

    // ================================================================== AzamPay

    @PostMapping("/azampay-auth/AppRegistration/GenerateToken")
    public ResponseEntity<Map<String, Object>> azamToken(@RequestBody JsonNode b) {
        if (switches.down("AGGREGATORS")) return ResponseEntity.status(503).build();
        if (!AZAM_APP.equals(text(b, "appName")) || !AZAM_CLIENT.equals(text(b, "clientId")) || !AZAM_SECRET.equals(text(b, "clientSecret"))) {
            return ResponseEntity.status(423).body(Map.of("message", "Provided detail is not valid for this app or secret key has expired", "success", false));
        }
        String t = UUID.randomUUID().toString().replace("-", "");
        tokens.put("AZAM:" + t, Instant.now().plusSeconds(3600));
        return ResponseEntity.ok(Map.of("data", Map.of("accessToken", t, "expire", OffsetDateTime.now().plusHours(1).toString()),
                "message", "Token generated successfully", "success", true, "statusCode", 200));
    }

    @PostMapping("/azampay/azampay/mno/checkout")
    public ResponseEntity<Map<String, Object>> azamCheckout(@RequestHeader(value = "Authorization", required = false) String auth,
                                                            @RequestHeader(value = "X-API-Key", required = false) String apiKey, @RequestBody JsonNode b) {
        if (switches.down("AGGREGATORS")) return ResponseEntity.status(503).build();
        if (!validToken("AZAM", auth) || !AZAM_KEY.equals(apiKey)) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized", "success", false));
        String provider = text(b, "provider");
        if (provider == null || !List.of("Mpesa", "Tigo", "Airtel", "Halopesa", "Azampesa").contains(provider)) {
            return ResponseEntity.badRequest().body(Map.of("errors", Map.of("provider", List.of("The provider field is not valid.")), "status", 400));
        }
        Order o = new Order();
        o.provider = "AZAMPAY";
        o.orderId = text(b, "externalId");
        o.phone = text(b, "accountNumber");
        o.network = provider;
        o.amount = new BigDecimal(text(b, "amount"));
        o.webhook = callbacks.get("AZAMPAY");
        orders.put(key(o.provider, o.orderId), o);
        schedule(o);
        return ResponseEntity.ok(Map.of("success", true, "transactionId", "AZ" + Math.abs(o.orderId.hashCode()), "message", "Your request has been received and is being processed."));
    }

    // ================================================================== ClickPesa

    @PostMapping("/clickpesa/third-parties/generate-token")
    public ResponseEntity<Map<String, Object>> clickToken(@RequestHeader(value = "client-id", required = false) String id,
                                                          @RequestHeader(value = "api-key", required = false) String key) {
        if (switches.down("AGGREGATORS")) return ResponseEntity.status(503).build();
        if (!CLICK_CLIENT.equals(id) || !CLICK_KEY.equals(key)) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        String t = UUID.randomUUID().toString().replace("-", "");
        tokens.put("CLICK:" + t, Instant.now().plusSeconds(3600));
        return ResponseEntity.ok(Map.of("success", true, "token", "Bearer " + t));
    }

    @PostMapping("/clickpesa/third-parties/payments/initiate-ussd-push-request")
    public ResponseEntity<Map<String, Object>> clickPush(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode b) {
        if (switches.down("AGGREGATORS")) return ResponseEntity.status(503).build();
        if (!validToken("CLICK", auth)) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        String ref = text(b, "orderReference");
        if (ref == null || !ref.matches("[A-Za-z0-9]+")) return ResponseEntity.badRequest().body(Map.of("message", "orderReference must be alphanumeric"));
        if (orders.containsKey(key("CLICKPESA", ref))) return ResponseEntity.status(409).body(Map.of("message", "Order reference already used"));
        Order o = new Order();
        o.provider = "CLICKPESA";
        o.orderId = ref;
        o.phone = text(b, "phoneNumber");
        o.amount = new BigDecimal(text(b, "amount"));
        o.webhook = callbacks.get("CLICKPESA");
        orders.put(key(o.provider, o.orderId), o);
        schedule(o);
        return ResponseEntity.ok(Map.of("id", "CP" + Math.abs(ref.hashCode()), "status", "PROCESSING", "orderReference", ref, "channel", "TIGO-PESA"));
    }

    @GetMapping("/clickpesa/third-parties/payments/{ref}")
    public ResponseEntity<?> clickStatus(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String ref) {
        if (!validToken("CLICK", auth)) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        Order o = orders.get(key("CLICKPESA", ref));
        if (o == null) return ResponseEntity.status(404).body(Map.of("message", "Payment not found"));
        return ResponseEntity.ok(List.of(clickData(o)));
    }

    private Map<String, Object> clickData(Order o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", "CP" + Math.abs(o.orderId.hashCode()));
        d.put("status", switch (o.status) { case "PAID" -> "SUCCESS"; case "FAILED" -> "FAILED"; default -> "PROCESSING"; });
        d.put("paymentReference", o.transId);
        d.put("orderReference", o.orderId);
        d.put("collectedAmount", o.amount.toPlainString());
        d.put("collectedCurrency", "TZS");
        d.put("message", o.message);
        d.put("channel", "TIGO-PESA");
        d.put("customer", Map.of("customerPhoneNumber", o.phone == null ? "" : o.phone));
        d.put("updatedAt", Instant.now().toString());
        return d;
    }

    // ================================================================== completing, and calling the institution back

    private void schedule(Order o) {
        timer.schedule(() -> complete(o), completeSeconds, TimeUnit.SECONDS);
    }

    void complete(Order o) {
        if (!"PENDING".equals(o.status)) return;
        if (o.phone != null && o.phone.endsWith("9")) {
            o.status = "FAILED";
            o.message = "Insufficient balance";
        } else {
            o.status = "PAID";
            o.transId = "TX" + (System.currentTimeMillis() % 10_000_000_000L);
            o.message = "Payment received";
        }
        notifyInstitution(o);
    }

    private void notifyInstitution(Order o) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("provider", o.provider);
        rec.put("orderId", o.orderId);
        rec.put("status", o.status);
        rec.put("at", Instant.now().toString());
        if (o.webhook == null) {
            rec.put("result", "not sent: no callback address");
        } else {
            try {
                Map<String, Object> body;
                Map<String, String> headers = new LinkedHashMap<>();
                switch (o.provider) {
                    case "SELCOM" -> {
                        body = new LinkedHashMap<>();
                        body.put("result", "PAID".equals(o.status) ? "SUCCESS" : "FAIL");
                        body.put("resultcode", "PAID".equals(o.status) ? "000" : "999");
                        body.put("order_id", o.orderId);
                        body.put("transid", o.transId == null ? "" : o.transId);
                        body.put("reference", o.transId == null ? "" : "SEL" + o.transId);
                        body.put("channel", "MPESA-TZ");
                        body.put("amount", o.amount.toPlainString());
                        body.put("phone", o.phone);
                        body.put("payment_status", "PAID".equals(o.status) ? "COMPLETED" : "REJECTED");
                        String ts = OffsetDateTime.now(ZoneId.of("Africa/Dar_es_Salaam")).format(TS);
                        headers.put("Authorization", "SELCOM " + Base64.getEncoder().encodeToString(SELCOM_KEY.getBytes(StandardCharsets.UTF_8)));
                        headers.put("Digest-Method", "HS256");
                        headers.put("Timestamp", ts);
                        headers.put("Signed-Fields", String.join(",", body.keySet()));
                        headers.put("Digest", digest(SELCOM_SECRET, ts, body));
                    }
                    case "AZAMPAY" -> {
                        body = new LinkedHashMap<>();
                        body.put("msisdn", o.phone);
                        body.put("amount", o.amount.toPlainString());
                        body.put("message", o.message);
                        body.put("utilityref", o.orderId);
                        body.put("operator", o.network);
                        body.put("reference", "AZ" + Math.abs(o.orderId.hashCode()));
                        body.put("transactionstatus", "PAID".equals(o.status) ? "success" : "failure");
                        body.put("submerchantAcc", null);
                        body.put("fspReferenceId", o.transId);
                    }
                    default -> body = Map.of("event", "PAID".equals(o.status) ? "PAYMENT RECEIVED" : "PAYMENT FAILED", "data", clickData(o));
                }
                String answer = http.post().uri(o.webhook).contentType(MediaType.APPLICATION_JSON).headers(x -> headers.forEach(x::set)).body(body)
                        .exchange((req, res) -> res.getStatusCode().value() + " " + new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                rec.put("result", answer.length() > 200 ? answer.substring(0, 200) : answer);
            } catch (RuntimeException e) {
                rec.put("result", "failed: " + e.getMessage());
            }
        }
        log.info("[aggregator mock] {} {} {}: {}", o.provider, o.orderId, o.status, rec.get("result"));
        sent.add(0, rec);
        if (sent.size() > 100) sent.remove(sent.size() - 1);
    }

    // ================================================================== developer

    @GetMapping("/mock/aggregators")
    public Map<String, Object> state() {
        return Map.of("orders", orders.values(), "callbacks", callbacks, "sent", sent, "completeSeconds", completeSeconds,
                "credentials", Map.of(
                        "SELCOM", Map.of("vendor", SELCOM_VENDOR, "apiKey", SELCOM_KEY, "apiSecret", SELCOM_SECRET, "baseUrl", "http://localhost:8098/selcom/v1"),
                        "AZAMPAY", Map.of("appName", AZAM_APP, "clientId", AZAM_CLIENT, "clientSecret", AZAM_SECRET, "apiKey", AZAM_KEY,
                                "baseUrl", "http://localhost:8098/azampay", "authUrl", "http://localhost:8098/azampay-auth"),
                        "CLICKPESA", Map.of("clientId", CLICK_CLIENT, "apiKey", CLICK_KEY, "baseUrl", "http://localhost:8098/clickpesa")));
    }

    /** Where AzamPay and ClickPesa notify the institution (CIMS › Integrations › Mobile money › notification address). Selcom takes it per order. */
    @PostMapping("/mock/aggregators/callback")
    public ResponseEntity<Void> setCallback(@RequestParam String provider, @RequestParam(required = false) String url) {
        if (url == null || url.isBlank()) callbacks.remove(provider.toUpperCase());
        else callbacks.put(provider.toUpperCase(), url.trim());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mock/aggregators/reset")
    public ResponseEntity<Void> reset() {
        orders.clear();
        sent.clear();
        tokens.clear();
        return ResponseEntity.noContent().build();
    }

    // ================================================================== helpers

    private boolean validToken(String kind, String auth) {
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        Instant until = tokens.get(kind + ":" + auth.substring(7).trim());
        return until != null && until.isAfter(Instant.now());
    }

    static String digest(String secret, String ts, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("timestamp=").append(ts);
        fields.forEach((k, v) -> sb.append('&').append(k).append('=').append(v == null ? "" : v));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String provider, String orderId) {
        return provider + ":" + orderId;
    }

    private static Map<String, String> lower(Map<String, String> h) {
        Map<String, String> m = new LinkedHashMap<>();
        h.forEach((k, v) -> m.put(k.toLowerCase(), v));
        return m;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    @SuppressWarnings("unused")
    private static boolean same(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
