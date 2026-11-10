package tz.co.msingi.cims.notification.sms;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * NextSMS, API v2 (nextsms.co.tz/api-docs). Every call carries the account's API token ({@code Authorization: Bearer …}, generated
 * under Customer Profile › Customization › API Keys; it acts as the customer that generated it, with that customer's approved sender
 * names). A message is sent to {@code /api/sms/v2/text/single} ({@code /api/sms/v2/test/text/single} in test mode: checked and
 * answered, never sent or charged) with the sender name in {@code from}; what became of it is at {@code /api/v2/reports?messageId=};
 * the balance at {@code /api/v2/balance}. Delivery callbacks are set under Customization › Webhooks.
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
        // the reference: up to 50 characters, no spaces
        if (reference != null) body.put("reference", reference.replaceAll("\\s", "").substring(0, Math.min(50, reference.replaceAll("\\s", "").length())));
        String path = a.live() ? "/api/sms/v2/text/single" : "/api/sms/v2/test/text/single";
        SmsHttp.Answer r = SmsHttp.post(SYSTEM, base(a) + path, body, h -> h.set("Authorization", auth(a)));
        if (!r.ok()) return new Sent(false, null, "Refused by NextSMS: " + SmsHttp.why(r));
        JsonNode m = r.body().path("messages").path(0);
        String id = SmsHttp.text(m, "messageId");
        String group = SmsHttp.text(m, "status", "groupName");
        // a refusal comes back as HTTP 200 with the reason in the message's status (and, for an unknown sender name, no message id)
        if ("REJECTED".equalsIgnoreCase(group) || "UNDELIVERABLE".equalsIgnoreCase(group)) {
            String d = SmsHttp.text(m, "status", "description");
            String name = SmsHttp.text(m, "status", "name");
            String why = "Refused by NextSMS: " + (d == null ? group : d);
            if ("REJECTED_SOURCE".equalsIgnoreCase(name)) {
                why += " The API token acts as the NextSMS customer that generated it: use a sender name approved for that customer (" + sender
                        + " is not), or ask NextSMS to register it.";
            } else if ("FAILED_SENDER".equalsIgnoreCase(name)) {
                why += " The sender name is not yet registered on the recipient's network.";
            }
            return new Sent(false, id, why);
        }
        if (id == null) return new Sent(false, null, "NextSMS gave no message id: " + SmsHttp.why(r));
        return new Sent(true, id, null);
    }

    @Override
    public Optional<Report> status(Account a, String providerRef, String phone) {
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, base(a) + "/api/v2/reports?messageId=" + URLEncoder.encode(providerRef, StandardCharsets.UTF_8),
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
        SmsHttp.Answer r = SmsHttp.get(SYSTEM, base(a) + "/api/v2/balance", h -> h.set("Authorization", auth(a)));
        if (!r.ok()) throw new IllegalStateException("NextSMS refused: " + SmsHttp.why(r));
        String display = SmsHttp.text(r.body(), "display");
        if (display != null) return display;
        String b = SmsHttp.text(r.body(), "sms_balance");
        return b == null ? r.body().toString() : b + ("price".equals(SmsHttp.text(r.body(), "type")) ? " TZS" : " SMS");
    }

    /** A delivery callback ({@code token}, {@code messageId}, {@code status}, {@code id}) or a list of reports. */
    @Override
    public List<Report> parseNotification(String body) {
        JsonNode n = SmsHttp.parse(body);
        List<Report> out = new ArrayList<>();
        JsonNode list = n.has("results") ? n.get("results") : n.isArray() ? n : null;
        if (list != null) list.forEach(x -> out.add(report(x)));
        else if (n.isObject()) out.add(report(n));
        return out.stream().filter(r -> r.providerRef() != null).toList();
    }

    /**
     * One report, whatever shape it comes in: a status object ({@code groupName}, {@code name}, {@code description}), a plain status
     * word (callbacks), and in the v2 reports a {@code delivery} part that is null until the network answers.
     */
    static Report report(JsonNode n) {
        JsonNode delivery = n.get("delivery");
        JsonNode st = delivery != null && !delivery.isNull() && !delivery.isMissingNode() ? delivery : n.get("status");
        String group = null, name = null, desc = null;
        if (st != null && st.isObject()) {
            group = SmsHttp.text(st, "groupName");
            if (group == null) group = SmsHttp.text(st, "status");
            name = SmsHttp.text(st, "name");
            desc = SmsHttp.text(st, "description");
        } else if (st != null && !st.isNull()) {
            group = st.asString();
        }
        String outcome = SmsHttp.outcome(group != null ? group : name);
        if ("PENDING".equals(outcome) && name != null) outcome = SmsHttp.outcome(name);
        String why = "FAILED".equals(outcome) ? (desc != null ? desc : name != null ? name : group) : null;
        return new Report(SmsHttp.text(n, "messageId"), SmsHttp.text(n, "to"), outcome, why);
    }

    private static String auth(Account a) {
        String t = a.secret("apiToken");
        return "Bearer " + (t == null ? "" : t.trim());
    }

    private static String base(Account a) {
        return SmsHttp.trimUrl(a.baseUrl() == null ? SmsProvider.NEXTSMS.defaultBaseUrl() : a.baseUrl());
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
