package tz.co.msingi.cims.finance.aggregator;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Selcom's checkout API (apigw…/v1), as Selcom publishes it for merchants:
 * <ul>
 *   <li>every call signed: {@code Authorization: SELCOM base64(apiKey)}, {@code Timestamp}, {@code Digest-Method: HS256},
 *       {@code Signed-Fields} and {@code Digest} = base64(HMAC-SHA256(apiSecret, "timestamp=…&amp;field=value…"));</li>
 *   <li>{@code POST /checkout/create-order-minimal} (vendor, order_id, buyer, amount, webhook as base64), then
 *       {@code POST /checkout/wallet-payment} (transid, order_id, msisdn) to prompt the phone;</li>
 *   <li>{@code GET /checkout/order-status?order_id=…}; a signed webhook is checked, an unsigned one only prompts CIMS to ask the
 *       order's status;</li>
 *   <li>INPROGRESS (111, 927), AMBIGUOUS (999) or no answer from the wallet push are never failures: the payment stays waiting
 *       until the order status says (Selcom's rule for these codes); the status is first asked after 3 minutes, then every 3 minutes
 *       (Selcom's guidance), and the webhook usually answers sooner;</li>
 *   <li>an order nobody paid is cancelled ({@code DELETE /checkout/cancel-order?order_id=…}) so it cannot be paid later.</li>
 * </ul>
 * Mandatory order fields (vendor, order_id, buyer_email, buyer_name, buyer_phone, amount, currency, no_of_items) are always sent.
 */
@Component
public class SelcomClient implements AggregatorClient {

    private static final String SYSTEM = "Selcom";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Override
    public AggregatorProvider provider() {
        return AggregatorProvider.SELCOM;
    }

    @Override
    public Result push(Account a, Push p) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("vendor", need(a, "vendor"));
        order.put("order_id", p.orderId());
        order.put("buyer_email", p.payerEmail() == null || p.payerEmail().isBlank() ? "payer@cims.ac.tz" : p.payerEmail());   // mandatory for Selcom
        order.put("buyer_name", p.payerName() == null || p.payerName().isBlank() ? "CIMS payer" : cut(p.payerName(), 100));       // mandatory for Selcom
        order.put("buyer_phone", p.phone());
        order.put("amount", p.amount().setScale(0, RoundingMode.CEILING).toBigInteger());   // a whole number of shillings, as in Selcom's examples
        order.put("currency", "TZS");
        order.put("buyer_remarks", cut(p.description(), 100));
        order.put("merchant_remarks", p.reference());
        order.put("no_of_items", 1);
        order.put("expiry", 30);   // minutes: CIMS closes an unanswered prompt after 30 minutes too
        if (a.notifyUrl() != null) order.put("webhook", Base64.getEncoder().encodeToString(a.notifyUrl().getBytes(StandardCharsets.UTF_8)));
        AggregatorHttp.Answer created = signedPost(a, "/checkout/create-order-minimal", order);
        if (!created.ok() || !success(created.body())) {
            return Result.of(State.FAILED, "Selcom did not take the order: " + AggregatorHttp.why(created));
        }
        Map<String, Object> wallet = new LinkedHashMap<>();
        wallet.put("transid", p.orderId());
        wallet.put("order_id", p.orderId());
        wallet.put("msisdn", p.phone());
        AggregatorHttp.Answer pushed;
        try {
            pushed = signedPost(a, "/checkout/wallet-payment", wallet);
        } catch (RuntimeException e) {
            pushed = new AggregatorHttp.Answer(0, null);   // no answer: the prompt may have gone out, so it is checked, not failed
        }
        if (unclear(pushed)) {
            // Selcom's rule: INPROGRESS (111) and AMBIGUOUS (927), or no clear answer, are not failures; the order's status decides
            return new Result(State.PENDING, AggregatorHttp.text(pushed.body(), "reference"), null, null, null, p.phone(),
                    "Waiting for Selcom to confirm", null);
        }
        if (!pushed.ok() || !success(pushed.body())) {
            return Result.of(State.FAILED, "Selcom could not prompt the phone: " + AggregatorHttp.why(pushed));
        }
        return new Result(State.PENDING, AggregatorHttp.text(pushed.body(), "reference"), null, null, null, p.phone(),
                AggregatorHttp.text(pushed.body(), "message"), null);
    }

    @Override
    public Result status(Account a, String orderId, String providerRef) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("order_id", orderId);
        Map<String, String> h = headers(a, q);
        AggregatorHttp.Answer ans = AggregatorHttp.get(SYSTEM, url(a, "/checkout/order-status") + "?order_id=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8),
                x -> h.forEach(x::set));
        if (!ans.ok()) return Result.of(State.UNKNOWN, AggregatorHttp.why(ans));
        JsonNode d = ans.body().path("data").isArray() && !ans.body().path("data").isEmpty() ? ans.body().path("data").get(0) : ans.body().path("data");
        return result(AggregatorHttp.text(d, "payment_status"), d, AggregatorHttp.text(ans.body(), "message"));
    }

    /**
     * Selcom's checkout webhook ({result, resultcode, order_id, transid, reference, channel, amount, phone, payment_status}). When it
     * carries Selcom's signature (Digest, Signed-Fields, Timestamp) the signature must be right; when it carries none, its content is
     * not believed and CIMS asks Selcom for the order's status instead (a signed call from CIMS).
     */
    @Override
    public Notification read(Account a, Map<String, String> headers, String body) {
        Map<String, String> h = AggregatorHttp.lower(headers);
        JsonNode b = AggregatorHttp.parse(body);
        String signed = h.get("signed-fields");
        String ts = h.get("timestamp");
        String digest = h.get("digest");
        if (signed == null || ts == null || digest == null) {
            String orderId = AggregatorHttp.text(b, "order_id");
            if (orderId == null) throw new SecurityException("Selcom notification without an order");
            return new Notification(orderId, null, status(a, orderId, null));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String f : signed.split(",")) fields.put(f.trim(), AggregatorHttp.text(b, f.trim()) == null ? "" : AggregatorHttp.text(b, f.trim()));
        String expected = digest(need(a, "apiSecret"), ts, fields);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), digest.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Selcom notification with a wrong signature");
        }
        String status = AggregatorHttp.text(b, "payment_status");
        if (status == null) status = "SUCCESS".equalsIgnoreCase(AggregatorHttp.text(b, "result")) ? "COMPLETED" : AggregatorHttp.text(b, "result");
        return new Notification(AggregatorHttp.text(b, "order_id"), null, result(status, b, AggregatorHttp.text(b, "message")));
    }

    @Override
    public int firstCheckAfterSeconds() {
        return 180;
    }

    @Override
    public int checkEverySeconds() {
        return 180;
    }

    @Override
    public void cancel(Account a, String orderId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("order_id", orderId);
        Map<String, String> h = headers(a, q);
        try {
            AggregatorHttp.delete(SYSTEM, url(a, "/checkout/cancel-order") + "?order_id=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8), x -> h.forEach(x::set));
        } catch (RuntimeException ignored) {
            // the order lapses at its expiry anyway
        }
    }

    @Override
    public void test(Account a) {
        Result r = status(a, "CIMS-TEST-" + System.currentTimeMillis(), null);
        if (r.state() == State.UNKNOWN && r.message() != null && r.message().contains("HTTP 401")) {
            throw new BusinessRuleException("AGGREGATOR_REFUSED", "Selcom refused the credentials: " + r.message());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Result result(String status, JsonNode d, String message) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        State st = switch (s) {
            case "COMPLETED", "SUCCESS", "PAID" -> State.PAID;
            case "CANCELLED", "USERCANCELED", "USERCANCELLED", "REJECTED", "FAILED", "FAIL" -> State.FAILED;
            case "PENDING", "INPROGRESS", "INPROCESS", "AMBIGUOUS", "AMBIGOUS" -> State.PENDING;
            default -> State.UNKNOWN;
        };
        return new Result(st, AggregatorHttp.text(d, "reference"), AggregatorHttp.text(d, "transid"), AggregatorHttp.amount(d, "amount"),
                AggregatorHttp.text(d, "channel"), AggregatorHttp.text(d, "phone"), message == null ? status : message,
                AggregatorHttp.time(AggregatorHttp.text(d, "creation_date")));
    }

    private AggregatorHttp.Answer signedPost(Account a, String path, Map<String, Object> body) {
        Map<String, String> h = headers(a, body);
        return AggregatorHttp.post(SYSTEM, url(a, path), body, x -> h.forEach(x::set));
    }

    static Map<String, String> headers(Account a, Map<String, Object> fields) {
        String ts = OffsetDateTime.now(ZoneId.of("Africa/Dar_es_Salaam")).format(TS);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "SELCOM " + Base64.getEncoder().encodeToString(need(a, "apiKey").getBytes(StandardCharsets.UTF_8)));
        h.put("Digest-Method", "HS256");
        h.put("Timestamp", ts);
        h.put("Signed-Fields", String.join(",", fields.keySet()));
        h.put("Digest", digest(need(a, "apiSecret"), ts, fields));
        return h;
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

    /** In progress, ambiguous, or no answer at all: the outcome is learnt from the order status or the webhook, never assumed. */
    private static boolean unclear(AggregatorHttp.Answer ans) {
        if (ans.status() == 0 || ans.status() >= 500) return true;
        String code = AggregatorHttp.text(ans.body(), "resultcode"), result = AggregatorHttp.text(ans.body(), "result");
        return "111".equals(code) || "927".equals(code) || "999".equals(code) || "INPROGRESS".equalsIgnoreCase(result)
                || "AMBIGUOUS".equalsIgnoreCase(result) || "AMBIGOUS".equalsIgnoreCase(result) || "PENDING".equalsIgnoreCase(result);
    }

    private static boolean success(JsonNode b) {
        return "000".equals(AggregatorHttp.text(b, "resultcode")) || "SUCCESS".equalsIgnoreCase(AggregatorHttp.text(b, "result"));
    }

    private static String url(Account a, String path) {
        return a.baseUrl().replaceAll("/+$", "") + path;
    }

    static String need(Account a, String field) {
        String v = a.field(field);
        if (v == null) throw new BusinessRuleException("AGGREGATOR_NOT_SET_UP", a.provider().label() + " is missing its " + field + " (Integrations › Mobile money).");
        return v;
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
