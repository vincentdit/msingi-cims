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
 * NextSMS (messaging-service.co.tz). Basic authentication with the account's username and password; a message is sent to
 * {@code /api/sms/v1/text/single} ({@code /api/sms/v1/test/text/single} in test mode: accepted and answered, never sent) with the
 * sender name in {@code from}; what became of it is at {@code /api/sms/v1/reports?messageId=}; the balance at
 * {@code /api/sms/v1/balance}.
 */
@Component
class NextSmsClient implements SmsProviderClient {

    private static final String SYSTEM = "NextSMS";

    @Override
    public SmsProvider provider() {
        return SmsProvider.NEXTSMS;
    }

    @Override
    public Sent send(Account a, String phone, String text, String sender, String reference, long messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", sender);
        body.put("to", phone);
        body.put("text", text);
        body.put("reference", reference);
        String path = a.live() ? "/api/sms/v1/text/single" : "/api/sms/v1/test/text/single";
        SmsHttp.Answer r = SmsHttp.post(SYSTEM, base(a) + path, body, h -> h.set("Authorization", auth(a)));
        if (!r.ok()) return new Sent(false, null, SmsHttp.why(r));
        JsonNode m = r.body().path("messages").path(0);
        String id = SmsHttp.text(m, "messageId");
        String group = SmsHttp.text(m, "status", "groupName");
        // a refusal comes back as HTTP 200 with the reason in the message's status (and, for an unknown sender name, no message id)
        if ("REJECTED".equalsIgnoreCase(group) || "UNDELIVERABLE".equalsIgnoreCase(group)) {
            String d = SmsHttp.text(m, "status", "description");
            String name = SmsHttp.text(m, "status", "name");
            String why = "Refused by NextSMS: " + (d == null ? group : d);
            if ("REJECTED_SOURCE".equalsIgnoreCase(name) || d != null && d.toLowerCase(java.util.Locale.ROOT).contains("sender")) {
                why += " Use a sender name already approved on the NextSMS account (" + sender + " is not), or ask NextSMS to register it.";
            }
            return new Sent(false, id, why);
        }
        if (id == null) return new Sent(false, null, "NextSMS gave no message id: " + SmsHttp.why(r));
        return new Sent(true, id, null);
    }

    @Override
    public Optional<Report> status(Account a, String providerRef, String phone) {
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, base(a) + "/api/sms/v1/reports?messageId=" + URLEncoder.encode(providerRef, StandardCharsets.UTF_8),
                h -> h.set("Authorization", auth(a)));
        if (!r.ok()) return Optional.empty();
        for (JsonNode n : r.body().path("results")) {
            Report rep = report(n);
            if (providerRef.equals(rep.providerRef())) return "PENDING".equals(rep.status()) ? Optional.empty() : Optional.of(rep);
        }
        return Optional.empty();
    }

    @Override
    public String balance(Account a) {
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, base(a) + "/api/sms/v1/balance", h -> h.set("Authorization", auth(a)));
        if (!r.ok()) throw new IllegalStateException("NextSMS refused: " + SmsHttp.why(r));
        String b = SmsHttp.text(r.body(), "sms_balance");
        return b == null ? r.body().toString() : b + " SMS";
    }

    @Override
    public List<Report> parseNotification(String body) {
        JsonNode n = SmsHttp.parse(body);
        List<Report> out = new ArrayList<>();
        JsonNode list = n.has("results") ? n.get("results") : n.isArray() ? n : null;
        if (list != null) list.forEach(x -> out.add(report(x)));
        else if (n.isObject()) out.add(report(n));
        return out.stream().filter(r -> r.providerRef() != null).toList();
    }

    private static Report report(JsonNode n) {
        String group = SmsHttp.text(n, "status", "groupName");
        if (group == null) group = SmsHttp.text(n, "status");
        String name = SmsHttp.text(n, "status", "name");
        String outcome = SmsHttp.outcome(group);
        String desc = SmsHttp.text(n, "status", "description");
        return new Report(SmsHttp.text(n, "messageId"), SmsHttp.text(n, "to"), outcome,
                "FAILED".equals(outcome) ? (desc != null ? desc : name != null ? name : group) : null);
    }

    private static String auth(Account a) {
        return SmsHttp.basic(a.setting("username"), a.secret("password"));
    }

    private static String base(Account a) {
        return SmsHttp.trimUrl(a.baseUrl() == null ? SmsProvider.NEXTSMS.defaultBaseUrl() : a.baseUrl());
    }
}
