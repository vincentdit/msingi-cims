package tz.co.msingi.cims.finance.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static tz.co.msingi.cims.finance.aggregator.SelcomClient.need;

/**
 * ClickPesa's API, as ClickPesa publishes it for merchants:
 * <ul>
 *   <li>a token: {@code POST /third-parties/generate-token} with headers {@code client-id} and {@code api-key} (the answer's token
 *       already starts with "Bearer"), valid for an hour;</li>
 *   <li>{@code POST /third-parties/payments/initiate-ussd-push-request} (amount, currency, orderReference, phoneNumber);</li>
 *   <li>{@code GET /third-parties/payments/{orderReference}} for the outcome. Its webhook is not signed, so CIMS asks ClickPesa
 *       about the payment before recording it.</li>
 * </ul>
 * Confirm field names against ClickPesa's document on onboarding.
 */
@Component
public class ClickPesaClient implements AggregatorClient {

    private static final String SYSTEM = "ClickPesa";
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, Instant until) {
    }

    @Override
    public AggregatorProvider provider() {
        return AggregatorProvider.CLICKPESA;
    }

    @Override
    public Result push(Account a, Push p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", p.amount().setScale(0, RoundingMode.CEILING).toPlainString());
        body.put("currency", "TZS");
        body.put("orderReference", p.orderId());
        body.put("phoneNumber", p.phone());
        AggregatorHttp.Answer ans = call(a, t -> AggregatorHttp.post(SYSTEM, url(a, "/third-parties/payments/initiate-ussd-push-request"), body, h -> h.set("Authorization", t)));
        if (!ans.ok()) return Result.of(State.FAILED, "ClickPesa could not prompt the phone: " + AggregatorHttp.why(ans));
        return new Result(State.PENDING, AggregatorHttp.text(ans.body(), "id"), null, null, null, p.phone(), AggregatorHttp.text(ans.body(), "status"), null);
    }

    @Override
    public Result status(Account a, String orderId, String providerRef) {
        AggregatorHttp.Answer ans = call(a, t -> AggregatorHttp.get(SYSTEM,
                url(a, "/third-parties/payments/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8)), h -> h.set("Authorization", t)));
        if (ans.status() == 404) return Result.of(State.PENDING, "Not yet known to ClickPesa");
        if (!ans.ok()) return Result.of(State.UNKNOWN, AggregatorHttp.why(ans));
        JsonNode d = ans.body().isArray() ? (ans.body().isEmpty() ? null : ans.body().get(0)) : ans.body();
        if (d == null) return Result.of(State.PENDING, "Not yet known to ClickPesa");
        return result(d);
    }

    /** ClickPesa's webhook ({event, data{orderReference, status …}}) only says which request to ask about. */
    @Override
    public Notification read(Account a, Map<String, String> headers, String body) {
        JsonNode b = AggregatorHttp.parse(body);
        JsonNode d = b.has("data") ? b.get("data") : b;
        return new Notification(AggregatorHttp.text(d, "orderReference"), null, result(d));
    }

    @Override
    public void test(Account a) {
        tokens.remove(key(a));
        token(a);
    }

    // ------------------------------------------------------------------ helpers

    static Result result(JsonNode d) {
        String s = AggregatorHttp.text(d, "status");
        String u = s == null ? "" : s.toUpperCase(Locale.ROOT);
        State st = switch (u) {
            case "SUCCESS", "SETTLED", "PAID" -> State.PAID;
            case "FAILED", "REJECTED", "CANCELLED", "EXPIRED" -> State.FAILED;
            case "PROCESSING", "PENDING", "INITIATED" -> State.PENDING;
            default -> State.UNKNOWN;
        };
        return new AggregatorClient.Result(st, AggregatorHttp.text(d, "id"),
                AggregatorHttp.text(d, "paymentReference") != null ? AggregatorHttp.text(d, "paymentReference") : AggregatorHttp.text(d, "id"),
                AggregatorHttp.amount(d, "collectedAmount") != null ? AggregatorHttp.amount(d, "collectedAmount") : AggregatorHttp.amount(d, "amount"),
                AggregatorHttp.text(d, "channel"), AggregatorHttp.text(d, "customer", "customerPhoneNumber"),
                AggregatorHttp.text(d, "message") == null ? s : AggregatorHttp.text(d, "message"), AggregatorHttp.time(AggregatorHttp.text(d, "updatedAt")));
    }

    private AggregatorHttp.Answer call(Account a, java.util.function.Function<String, AggregatorHttp.Answer> f) {
        AggregatorHttp.Answer ans = f.apply(token(a));
        if (ans.status() == 401) {
            tokens.remove(key(a));
            ans = f.apply(token(a));
        }
        return ans;
    }

    private String token(Account a) {
        Token t = tokens.get(key(a));
        if (t != null && Instant.now().isBefore(t.until())) return t.value();
        String id = need(a, "clientId");
        String key = need(a, "apiKey");
        AggregatorHttp.Answer ans = AggregatorHttp.post(SYSTEM, url(a, "/third-parties/generate-token"), Map.of(), h -> {
            h.set("client-id", id);
            h.set("api-key", key);
        });
        String value = AggregatorHttp.text(ans.body(), "token");
        if (!ans.ok() || value == null) throw new BusinessRuleException("AGGREGATOR_REFUSED", "ClickPesa refused the credentials: " + AggregatorHttp.why(ans));
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) value = "Bearer " + value;
        tokens.put(key(a), new Token(value, Instant.now().plusSeconds(3300)));
        return value;
    }

    private static String key(Account a) {
        return a.field("clientId") + "|" + a.live();
    }

    private static String url(Account a, String path) {
        return a.baseUrl().replaceAll("/+$", "") + path;
    }
}
