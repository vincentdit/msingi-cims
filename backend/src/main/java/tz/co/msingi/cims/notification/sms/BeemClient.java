package tz.co.msingi.cims.notification.sms;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Beem Africa. Basic authentication with the API key and secret key; a message is sent to {@code /v1/send} with the sender name
 * in {@code source_addr} and CIMS's message number as the recipient id; delivery reports are asked for at the separate report
 * address ({@code /public/v1/delivery-reports?dest_addr=&request_id=}) or posted by Beem to the address set on its dashboard;
 * the balance is at {@code /public/v1/vendors/balance}. Beem has no test mode: every accepted message is sent.
 */
@Component
class BeemClient implements SmsProviderClient {

    private static final String SYSTEM = "Beem";

    @Override
    public SmsProvider provider() {
        return SmsProvider.BEEM;
    }

    @Override
    public Sent send(Account a, String phone, String text, String sender, String reference, long messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_addr", sender);
        body.put("encoding", 0);
        body.put("schedule_time", "");
        body.put("message", text);
        body.put("recipients", List.of(Map.of("recipient_id", messageId, "dest_addr", phone)));
        SmsHttp.Answer r = SmsHttp.post(SYSTEM, base(a) + "/v1/send", body, h -> h.set("Authorization", auth(a)));
        String id = SmsHttp.text(r.body(), "request_id");
        boolean ok = r.ok() && "true".equalsIgnoreCase(SmsHttp.text(r.body(), "successful")) && id != null;
        if (!ok) return new Sent(false, null, "Refused by Beem: " + SmsHttp.why(r));
        String valid = SmsHttp.text(r.body(), "valid");
        if ("0".equals(valid)) return new Sent(false, id, "Beem found the number invalid");
        return new Sent(true, id, null);
    }

    @Override
    public Optional<Report> status(Account a, String providerRef, String phone) {
        String url = report(a) + "/public/v1/delivery-reports?dest_addr=" + URLEncoder.encode(phone, StandardCharsets.UTF_8)
                + "&request_id=" + URLEncoder.encode(providerRef, StandardCharsets.UTF_8);
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, url, h -> h.set("Authorization", auth(a)));
        if (!r.ok()) return Optional.empty();
        JsonNode list = r.body().isArray() ? r.body() : r.body().path("data");
        for (JsonNode n : list) {
            Report rep = report(n, providerRef);
            if (!"PENDING".equals(rep.status())) return Optional.of(rep);
        }
        if (r.body().isObject() && r.body().has("status")) {
            Report rep = report(r.body(), providerRef);
            if (!"PENDING".equals(rep.status())) return Optional.of(rep);
        }
        return Optional.empty();
    }

    @Override
    public String balance(Account a) {
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, base(a) + "/public/v1/vendors/balance", h -> h.set("Authorization", auth(a)));
        if (!r.ok()) throw new IllegalStateException("Beem refused: " + SmsHttp.why(r));
        String b = SmsHttp.text(r.body(), "data", "credit_balance");
        return b == null ? r.body().toString() : b + " credits";
    }

    @Override
    public List<Report> parseNotification(String body) {
        JsonNode n = SmsHttp.parse(body);
        List<Report> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> out.add(report(x, null)));
        else if (n.isObject()) out.add(report(n, null));
        return out.stream().filter(r -> r.providerRef() != null).toList();
    }

    private static Report report(JsonNode n, String fallbackRef) {
        String ref = SmsHttp.text(n, "request_id");
        String status = SmsHttp.text(n, "status");
        String outcome = SmsHttp.outcome(status);
        return new Report(ref == null ? fallbackRef : ref, SmsHttp.text(n, "dest_addr"), outcome, "FAILED".equals(outcome) ? "Not delivered (" + status + ")" : null);
    }

    private static String auth(Account a) {
        return SmsHttp.basic(a.setting("apiKey"), a.secret("secretKey"));
    }

    private static String base(Account a) {
        return SmsHttp.trimUrl(a.baseUrl() == null ? SmsProvider.BEEM.defaultBaseUrl() : a.baseUrl());
    }

    private static String report(Account a) {
        return SmsHttp.trimUrl(a.reportUrl() == null ? SmsProvider.BEEM.defaultReportUrl() : a.reportUrl());
    }
}
