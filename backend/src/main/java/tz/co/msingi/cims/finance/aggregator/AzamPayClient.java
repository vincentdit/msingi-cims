package tz.co.msingi.cims.finance.aggregator;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static tz.co.msingi.cims.finance.aggregator.SelcomClient.need;

/**
 * AzamPay's checkout API, as AzamPay publishes it for merchants:
 * <ul>
 *   <li>a token from the authenticator: {@code POST /AppRegistration/GenerateToken} (appName, clientId, clientSecret), kept until
 *       shortly before it expires;</li>
 *   <li>{@code POST /azampay/mno/checkout} with {@code Authorization: Bearer} and {@code X-API-Key}: accountNumber (the payer's
 *       phone), amount, currency, externalId (CIMS's order id) and provider (Mpesa, Tigo, Airtel, Halopesa, Azampesa);</li>
 *   <li>the callback AzamPay posts to the address registered in its portal (msisdn, amount, utilityref = externalId, reference,
 *       transactionstatus). AzamPay cannot be asked about a request, so CIMS waits for the callback (and the statement).</li>
 * </ul>
 * Confirm field names against AzamPay's document on onboarding.
 */
@Component
public class AzamPayClient implements AggregatorClient {

    private static final String SYSTEM = "AzamPay";
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, Instant until) {
    }

    @Override
    public AggregatorProvider provider() {
        return AggregatorProvider.AZAMPAY;
    }

    @Override
    public Result push(Account a, Push p) {
        if (p.network() == null) throw new BusinessRuleException("NETWORK_REQUIRED", "Choose the mobile money service the phone uses.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountNumber", p.phone());
        body.put("amount", p.amount().setScale(0, RoundingMode.CEILING).toPlainString());
        body.put("currency", "TZS");
        body.put("externalId", p.orderId());
        body.put("provider", network(p.network()));
        body.put("additionalProperties", Map.of("reference", p.reference()));
        AggregatorHttp.Answer ans = checkout(a, body, false);
        if (ans.status() == 401) {
            tokens.remove(key(a));
            ans = checkout(a, body, true);
        }
        if (!ans.ok() || ans.body().path("success").isBoolean() && !ans.body().path("success").asBoolean()) {
            return Result.of(State.FAILED, "AzamPay could not prompt the phone: " + AggregatorHttp.why(ans));
        }
        return new Result(State.PENDING, AggregatorHttp.text(ans.body(), "transactionId"), null, null, null, p.phone(),
                AggregatorHttp.text(ans.body(), "message"), null);
    }

    @Override
    public Result status(Account a, String orderId, String providerRef) {
        return Result.of(State.UNKNOWN, "AzamPay is not asked; waiting for its callback");
    }

    /** The callback's own password is not used: the notification address carries the institution's secret token. */
    @Override
    public Notification read(Account a, Map<String, String> headers, String body) {
        JsonNode b = AggregatorHttp.parse(body);
        String status = AggregatorHttp.text(b, "transactionstatus");
        if (status == null) status = AggregatorHttp.text(b, "transactionStatus");
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        State st = s.equals("success") || s.equals("successful") ? State.PAID : s.startsWith("fail") || s.equals("cancelled") ? State.FAILED : State.UNKNOWN;
        String orderId = AggregatorHttp.text(b, "utilityref");
        if (orderId == null) orderId = AggregatorHttp.text(b, "externalreference");
        return new Notification(orderId, null, new Result(st, AggregatorHttp.text(b, "reference"),
                firstNonNull(AggregatorHttp.text(b, "fspReferenceId"), AggregatorHttp.text(b, "reference")), AggregatorHttp.amount(b, "amount"),
                AggregatorHttp.text(b, "operator"), AggregatorHttp.text(b, "msisdn"), AggregatorHttp.text(b, "message"), OffsetDateTime.now()));
    }

    @Override
    public void test(Account a) {
        tokens.remove(key(a));
        token(a);
    }

    // ------------------------------------------------------------------ helpers

    private AggregatorHttp.Answer checkout(Account a, Map<String, Object> body, boolean renewed) {
        String t = token(a);
        String apiKey = need(a, "apiKey");
        return AggregatorHttp.post(SYSTEM, a.baseUrl().replaceAll("/+$", "") + "/azampay/mno/checkout", body, h -> {
            h.setBearerAuth(t);
            h.set("X-API-Key", apiKey);
        });
    }

    private String token(Account a) {
        Token t = tokens.get(key(a));
        if (t != null && Instant.now().isBefore(t.until())) return t.value();
        Map<String, Object> body = Map.of("appName", need(a, "appName"), "clientId", need(a, "clientId"), "clientSecret", need(a, "clientSecret"));
        String auth = a.authUrl() == null ? a.provider().defaultAuthUrl(a.live()) : a.authUrl();
        AggregatorHttp.Answer ans = AggregatorHttp.post(SYSTEM, auth.replaceAll("/+$", "") + "/AppRegistration/GenerateToken", body, h -> { });
        String value = AggregatorHttp.text(ans.body(), "data", "accessToken");
        if (!ans.ok() || value == null) throw new BusinessRuleException("AGGREGATOR_REFUSED", "AzamPay refused the credentials: " + AggregatorHttp.why(ans));
        OffsetDateTime expires = AggregatorHttp.time(AggregatorHttp.text(ans.body(), "data", "expire"));
        Instant until = expires == null ? Instant.now().plusSeconds(1800) : expires.toInstant().minusSeconds(60);
        tokens.put(key(a), new Token(value, until));
        return value;
    }

    private static String key(Account a) {
        return a.field("clientId") + "|" + a.live();
    }

    /** AzamPay's names for the mobile money services. */
    static String network(String n) {
        return switch (n.toUpperCase(Locale.ROOT)) {
            case "MPESA" -> "Mpesa";
            case "MIXX_BY_YAS", "TIGO" -> "Tigo";
            case "AIRTEL_MONEY", "AIRTEL" -> "Airtel";
            case "HALOPESA" -> "Halopesa";
            case "AZAMPESA" -> "Azampesa";
            default -> throw new BusinessRuleException("NETWORK_NOT_SUPPORTED", "AzamPay does not take this mobile money service.");
        };
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
