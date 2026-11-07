package tz.co.msingi.cims.notification.sms;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The account Msingi holds with an SMS provider (NextSMS or Beem), set in the Msingi console: the provider's credentials (secrets
 * encrypted, never shown again), test or live, Msingi's own sender name (used for an institution whose name is not approved yet)
 * and the address the provider posts delivery reports to.
 */
@Service
@RequiredArgsConstructor
public class SmsProviderService {

    public record FieldView(String name, String label, boolean secret, String value, boolean set) {
    }

    public record ProviderView(String code, String label, List<SmsProvider.Field> fields, String baseUrl, String reportUrl) {
    }

    public record View(String mode, String provider, String providerLabel, String environment, boolean active, List<FieldView> fields,
                       String baseUrl, String reportUrl, String defaultSender, String notifyUrl, OffsetDateTime lastTestAt, Boolean lastTestOk,
                       String lastTestNote, String updatedByName, OffsetDateTime updatedAt, List<ProviderView> providers, Long balance, OffsetDateTime balanceAt,
                       Long outstandingUnits) {
    }

    public record SaveRequest(@NotNull SmsProvider provider, @NotNull String environment, boolean active, Map<String, String> fields,
                              @Size(max = 200) String baseUrl, @Size(max = 200) String reportUrl,
                              @NotNull @Pattern(regexp = SenderNameService.SENDER_PATTERN, message = "3 to 11 letters or digits, no spaces")
                              String defaultSender) {
    }

    public record TestSms(@NotNull String phone) {
    }

    /** The account in force, with the sender name to use when an institution's own is not approved. */
    public record Active(SmsProviderClient.Account account, String defaultSender) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final PlatformAuditService audit;
    private final List<SmsProviderClient> clientList;
    private final SecureRandom random = new SecureRandom();

    @Value("${cims.sms.mode:simulated}")
    private String mode;
    @Value("${cims.sms.callback-base-url:https://app.cims.ac.tz}")
    private String callbackBase;

    public SmsProviderClient client(SmsProvider p) {
        Map<SmsProvider, SmsProviderClient> m = new EnumMap<>(SmsProvider.class);
        clientList.forEach(c -> m.put(c.provider(), c));
        SmsProviderClient c = m.get(p);
        if (c == null) throw new IllegalStateException("No adapter for " + p);
        return c;
    }

    public boolean live() {
        return "live".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    @Transactional(readOnly = true)
    public View view() {
        List<ProviderView> providers = new ArrayList<>();
        for (SmsProvider p : SmsProvider.values()) providers.add(new ProviderView(p.name(), p.label(), p.fields(), p.defaultBaseUrl(), p.defaultReportUrl()));
        Map<String, Object> r = row();
        String prov = (String) r.get("provider");
        SmsProvider p = prov == null ? null : SmsProvider.valueOf(prov);
        List<FieldView> fields = List.of();
        if (p != null) {
            Map<String, String> plain = json((String) r.get("settings"));
            Map<String, String> secrets = secrets(r);
            fields = p.fields().stream().map(f -> new FieldView(f.name(), f.label(), f.secret(), f.secret() ? null : plain.get(f.name()),
                    f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).toList();
        }
        return new View(live() ? "live" : "simulated", prov, p == null ? null : p.label(), (String) r.get("environment"), Boolean.TRUE.equals(r.get("active")),
                fields, (String) r.get("base_url"), (String) r.get("report_url"), (String) r.get("default_sender"), p == null ? null : notifyUrl(p, r),
                time(r.get("last_test_at")), (Boolean) r.get("last_test_ok"), (String) r.get("last_test_note"), (String) r.get("updated_by_name"),
                time(r.get("updated_at")), providers, r.get("last_balance") == null ? null : ((Number) r.get("last_balance")).longValue(),
                time(r.get("last_balance_at")), r.get("outstanding_units") == null ? null : ((Number) r.get("outstanding_units")).longValue());
    }

    @Transactional
    public View save(SaveRequest req) {
        String environment = "LIVE".equalsIgnoreCase(req.environment()) ? "LIVE" : "SANDBOX";
        Map<String, Object> r = row();
        boolean sameProvider = req.provider().name().equals(r.get("provider"));
        Map<String, String> plain = new LinkedHashMap<>();
        Map<String, String> secrets = sameProvider ? new LinkedHashMap<>(secrets(r)) : new LinkedHashMap<>();
        Map<String, String> given = req.fields() == null ? Map.of() : req.fields();
        boolean secretsChanged = false;
        for (SmsProvider.Field f : req.provider().fields()) {
            String v = given.get(f.name());
            if (v == null || v.isBlank()) continue;
            if (f.secret()) {
                secrets.put(f.name(), v.trim());
                secretsChanged = true;
            } else {
                plain.put(f.name(), v.trim());
            }
        }
        if (req.active()) {
            List<String> missing = req.provider().fields().stream()
                    .filter(f -> !(f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).map(SmsProvider.Field::label).toList();
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("SMS_PROVIDER_INCOMPLETE", req.provider().label() + " cannot be switched on without: " + String.join(", ", missing) + ".");
            }
        }
        jdbc.update("""
                update platform_sms_settings set provider = ?, environment = ?, active = ?, settings = ?, secrets_enc = ?, base_url = ?, report_url = ?,
                  default_sender = ?, notify_token_enc = coalesce(notify_token_enc, ?), updated_by_name = ?, updated_at = now(),
                  last_test_at = case when ? then last_test_at end, last_test_ok = case when ? then last_test_ok end,
                  last_test_note = case when ? then last_test_note end
                where id = 1""",
                req.provider().name(), environment, req.active(), toJson(plain), secrets.isEmpty() ? null : cipher.encrypt(toJson(secrets)),
                blank(req.baseUrl()), blank(req.reportUrl()), req.defaultSender().trim(), cipher.encrypt(newToken()), CurrentUser.platformName(),
                sameProvider, sameProvider, sameProvider);
        Map<String, Object> details = new HashMap<>();
        details.put("provider", req.provider().name());
        details.put("environment", environment);
        details.put("active", req.active());
        details.put("defaultSender", req.defaultSender().trim());
        details.put("secretsChanged", secretsChanged);
        audit.record("SMS_PROVIDER_UPDATED", null, "PlatformSmsSettings", 1L, details);
        return view();
    }

    /** Proves the credentials by reading the account's balance, and keeps the outcome for the console page. */
    @Transactional
    public View test() {
        SmsProviderClient.Account a = account(row()).orElseThrow(() -> new BusinessRuleException("SMS_PROVIDER_NOT_SET_UP", "Save the SMS provider first."));
        boolean ok;
        String note;
        try {
            note = a.provider().label() + " accepted the credentials. Balance: " + client(a.provider()).balance(a) + ".";
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
            note = e.getMessage();
        }
        recordTest(ok, note);
        return view();
    }

    /** Sends one message to a phone under Msingi's own sender name: proves sending end to end (in test mode nothing is sent). */
    @Transactional
    public View sendTest(TestSms req) {
        Map<String, Object> r = row();
        SmsProviderClient.Account a = account(r).orElseThrow(() -> new BusinessRuleException("SMS_PROVIDER_NOT_SET_UP", "Save the SMS provider first."));
        String phone = PhoneNumbers.normalise(req.phone());
        String sender = (String) r.get("default_sender");
        boolean ok;
        String note;
        try {
            SmsProviderClient.Sent s = client(a.provider()).send(a, phone, "CIMS test message from Msingi Technologies: SMS sending works.", sender,
                    "test-" + System.currentTimeMillis(), 0);
            ok = s.accepted();
            note = ok ? a.provider().label() + " accepted the test message to " + PhoneNumbers.mask(phone) + " from " + sender + " (id " + s.providerRef() + ")"
                    + (a.live() ? "." : "; test mode: nothing is actually sent.")
                    : s.error();
        } catch (RuntimeException e) {
            ok = false;
            note = e.getMessage();
        }
        recordTest(ok, note);
        audit.record("SMS_PROVIDER_TEST_SENT", null, "PlatformSmsSettings", 1L, Map.of("ok", ok));
        return view();
    }

    /** A new delivery-report address: the provider must be given it, and the old one stops working at once. */
    @Transactional
    public View newNotifyAddress() {
        jdbc.update("update platform_sms_settings set notify_token_enc = ?, updated_at = now() where id = 1", cipher.encrypt(newToken()));
        audit.record("SMS_NOTIFY_ADDRESS_CHANGED", null, "PlatformSmsSettings", 1L, Map.of());
        return view();
    }

    /** The account messages go through: switched on, complete. Empty while no provider is switched on. */
    @Transactional(readOnly = true)
    public Optional<Active> active() {
        Map<String, Object> r = row();
        if (!Boolean.TRUE.equals(r.get("active"))) return Optional.empty();
        return account(r).map(a -> new Active(a, (String) r.get("default_sender")));
    }

    @Transactional(readOnly = true)
    public String defaultSender() {
        return (String) row().get("default_sender");
    }

    /** Whether a delivery report came to Msingi's own address for the provider in force. */
    @Transactional(readOnly = true)
    public boolean tokenMatches(SmsProvider provider, String token) {
        Map<String, Object> r = row();
        if (token == null || !provider.name().equals(r.get("provider")) || r.get("notify_token_enc") == null) return false;
        String mine = cipher.decrypt((String) r.get("notify_token_enc"));
        return MessageDigest.isEqual(mine.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private void recordTest(boolean ok, String note) {
        jdbc.update("update platform_sms_settings set last_test_at = now(), last_test_ok = ?, last_test_note = ? where id = 1", ok,
                note == null ? null : note.length() > 300 ? note.substring(0, 300) : note);
    }

    private Optional<SmsProviderClient.Account> account(Map<String, Object> r) {
        if (r.get("provider") == null) return Optional.empty();
        SmsProvider p = SmsProvider.valueOf((String) r.get("provider"));
        return Optional.of(new SmsProviderClient.Account(p, "LIVE".equals(r.get("environment")), json((String) r.get("settings")), secrets(r),
                (String) r.get("base_url"), (String) r.get("report_url")));
    }

    private String notifyUrl(SmsProvider p, Map<String, Object> r) {
        if (r.get("notify_token_enc") == null) return null;
        return callbackBase.replaceAll("/+$", "") + "/api/integrations/sms/" + p.name().toLowerCase() + "/" + cipher.decrypt((String) r.get("notify_token_enc"));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select * from platform_sms_settings where id = 1");
    }

    private Map<String, String> secrets(Map<String, Object> r) {
        String enc = (String) r.get("secrets_enc");
        return enc == null ? Map.of() : json(cipher.decrypt(enc));
    }

    private static Map<String, String> json(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return SmsHttp.JSON.readValue(s, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toJson(Map<String, String> m) {
        try {
            return SmsHttp.JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime time(Object o) {
        if (o instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return (OffsetDateTime) o;
    }

    private String newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
