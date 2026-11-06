package tz.co.msingi.cims.finance.aggregator;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.collection.PaymentChannelService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.links.PortalLinks;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The institution's merchant account with a payment aggregator (Integrations › Mobile money): which aggregator, sandbox or live,
 * the credentials it issued (secrets encrypted, never shown again), and the address the aggregator notifies CIMS at. That address
 * carries a secret token of the institution's own, so a notification is believed only when it comes to the right address (and,
 * where the aggregator signs or can be asked, only when the signature or the aggregator's own answer agrees).
 */
@Service
@RequiredArgsConstructor
public class CollectionAccountService {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final PortalLinks links;
    private final PaymentChannelService channels;
    private final AuditService audit;
    private final AggregatorClients clients;
    private final tz.co.msingi.cims.finance.collection.CollectionProperties collection;
    private final org.springframework.core.env.Environment env;
    private final SecureRandom random = new SecureRandom();

    /** The rehearsal records payments without money: only on a test system, or while CIMS_ALLOW_PAYMENT_SIMULATION is on. */
    public boolean rehearsalAllowed() {
        return collection.isSimulated() || Boolean.parseBoolean(env.getProperty("cims.payments.allow-simulation", "false"));
    }

    public record FieldView(String name, String label, boolean secret, String value, boolean set) {
    }

    public record ProviderView(String code, String label, List<AggregatorProvider.Field> fields, String sandboxUrl, String liveUrl,
                               boolean statusQuery, boolean needsNetwork) {
    }

    public record View(boolean available, String provider, String providerLabel, String environment, boolean active, boolean phonePrompt,
                       List<FieldView> fields, String baseUrl, String authUrl, String defaultUrl, String notifyUrl,
                       OffsetDateTime lastTestAt, Boolean lastTestOk, String lastTestNote, List<ProviderView> providers, boolean rehearsalAllowed) {
    }

    public record SaveRequest(@NotNull AggregatorProvider provider, @NotNull String environment, boolean active, boolean phonePrompt,
                              Map<String, String> fields, @Size(max = 200) String baseUrl, @Size(max = 200) String authUrl) {
    }

    @Transactional(readOnly = true)
    public View view() {
        List<ProviderView> providers = new ArrayList<>();
        for (AggregatorProvider p : AggregatorProvider.values()) {
            providers.add(new ProviderView(p.name(), p.label(), p.fields(), p.defaultUrl(false), p.defaultUrl(true), p.statusQuery(), p.needsNetwork()));
        }
        boolean available = !channels.usesGepg();
        Map<String, Object> r = row();
        if (r == null) {
            return new View(available, null, null, "SANDBOX", false, true, List.of(), null, null, null, null, null, null, null, providers, rehearsalAllowed());
        }
        AggregatorProvider p = AggregatorProvider.valueOf((String) r.get("provider"));
        Map<String, String> plain = json((String) r.get("settings"));
        Map<String, String> secrets = secrets(r);
        List<FieldView> fields = p.fields().stream().map(f -> new FieldView(f.name(), f.label(), f.secret(), f.secret() ? null : plain.get(f.name()),
                f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).toList();
        boolean live = "LIVE".equals(r.get("environment"));
        Object tested = r.get("last_test_at");
        return new View(available, p.name(), p.label(), (String) r.get("environment"), (Boolean) r.get("active"), (Boolean) r.get("phone_prompt"),
                fields, (String) r.get("base_url"), (String) r.get("auth_url"), p.defaultUrl(live), notifyUrl(p, r),
                tested instanceof java.sql.Timestamp t ? t.toInstant().atOffset(java.time.ZoneOffset.UTC) : (OffsetDateTime) tested,
                (Boolean) r.get("last_test_ok"), (String) r.get("last_test_note"), providers, rehearsalAllowed());
    }

    @Transactional
    public View save(SaveRequest req) {
        if (channels.usesGepg()) {
            throw new BusinessRuleException("GEPG_INSTITUTION", "Public institutions are paid through GePG: an aggregator is for private and faith-based institutions.");
        }
        if (req.provider() == AggregatorProvider.SIMULATED && req.active() && !rehearsalAllowed()) {
            throw new BusinessRuleException("REHEARSAL_NOT_ALLOWED", "The rehearsal records payments without money and is only available while test payments are allowed on this server.");
        }
        String environment = "LIVE".equalsIgnoreCase(req.environment()) ? "LIVE" : "SANDBOX";
        Map<String, Object> r = row();
        boolean sameProvider = r != null && req.provider().name().equals(r.get("provider"));
        Map<String, String> plain = new LinkedHashMap<>();
        Map<String, String> secrets = sameProvider ? new LinkedHashMap<>(secrets(r)) : new LinkedHashMap<>();
        Map<String, String> given = req.fields() == null ? Map.of() : req.fields();
        for (AggregatorProvider.Field f : req.provider().fields()) {
            String v = given.get(f.name());
            if (f.secret()) {
                if (v != null && !v.isBlank()) secrets.put(f.name(), v.trim());
            } else if (v != null && !v.isBlank()) {
                plain.put(f.name(), v.trim());
            }
        }
        if (req.active() && req.provider() != AggregatorProvider.SIMULATED) {
            List<String> missing = req.provider().fields().stream()
                    .filter(f -> !(f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).map(AggregatorProvider.Field::label).toList();
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("AGGREGATOR_INCOMPLETE", req.provider().label() + " cannot be switched on without: " + String.join(", ", missing) + ".");
            }
        }
        String secretsEnc = secrets.isEmpty() ? null : cipher.encrypt(toJson(secrets));
        if (r == null) {
            jdbc.update("""
                    insert into collection_accounts (tenant_id, provider, environment, active, phone_prompt, settings, secrets_enc, base_url, auth_url,
                      notify_token_enc, updated_by_name) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    req.provider().name(), environment, req.active(), req.phonePrompt(), toJson(plain), secretsEnc, blank(req.baseUrl()), blank(req.authUrl()),
                    cipher.encrypt(newToken()), CurrentUser.name());
        } else {
            jdbc.update("""
                    update collection_accounts set provider = ?, environment = ?, active = ?, phone_prompt = ?, settings = ?, secrets_enc = ?, base_url = ?,
                      auth_url = ?, updated_by_name = ?, updated_at = now(),
                      last_test_at = case when ? then last_test_at end, last_test_ok = case when ? then last_test_ok end,
                      last_test_note = case when ? then last_test_note end""",
                    req.provider().name(), environment, req.active(), req.phonePrompt(), toJson(plain), secretsEnc, blank(req.baseUrl()), blank(req.authUrl()),
                    CurrentUser.name(), sameProvider, sameProvider, sameProvider);
        }
        Map<String, Object> details = new HashMap<>();
        details.put("provider", req.provider().name());
        details.put("environment", environment);
        details.put("active", req.active());
        details.put("secretsChanged", given.entrySet().stream().anyMatch(e -> req.provider().fields().stream()
                .anyMatch(f -> f.secret() && f.name().equals(e.getKey()) && e.getValue() != null && !e.getValue().isBlank())));
        audit.record("COLLECTION_ACCOUNT_UPDATED", "CollectionAccount", null, details);
        return view();
    }

    /** A new notification address: the aggregator must be given it, and the old one stops working at once. */
    @Transactional
    public View newNotifyAddress() {
        if (row() == null) throw new BusinessRuleException("AGGREGATOR_NOT_SET_UP", "Save the aggregator first.");
        jdbc.update("update collection_accounts set notify_token_enc = ?, updated_at = now()", cipher.encrypt(newToken()));
        audit.record("COLLECTION_NOTIFY_ADDRESS_CHANGED", "CollectionAccount", null, Map.of());
        return view();
    }

    /** Proves the credentials with the aggregator, and keeps the outcome for the settings page. */
    @Transactional
    public View test() {
        AggregatorClient.Account a = load().orElseThrow(() -> new BusinessRuleException("AGGREGATOR_NOT_SET_UP", "Save the aggregator first."));
        boolean ok;
        String note;
        try {
            clients.of(a.provider()).test(a);
            ok = true;
            note = a.provider().label() + " accepted the credentials.";
        } catch (RuntimeException e) {
            ok = false;
            note = e.getMessage();
        }
        jdbc.update("update collection_accounts set last_test_at = now(), last_test_ok = ?, last_test_note = ?", ok, note == null ? null : note.length() > 300 ? note.substring(0, 300) : note);
        return view();
    }

    /** The institution's aggregator account, when one is switched on (and the institution is not paid through GePG). */
    @Transactional(readOnly = true)
    public Optional<AggregatorClient.Account> active() {
        Map<String, Object> r = row();
        if (r == null || !Boolean.TRUE.equals(r.get("active")) || channels.usesGepg()) return Optional.empty();
        if (AggregatorProvider.SIMULATED.name().equals(r.get("provider")) && !rehearsalAllowed()) return Optional.empty();
        return Optional.of(account(r));
    }

    /** Whether payers can be prompted on their phone, and whether they must name their network. */
    @Transactional(readOnly = true)
    public PaymentChannelService.PhonePrompt phonePrompt() {
        Map<String, Object> r = row();
        if (r == null || !Boolean.TRUE.equals(r.get("active")) || !Boolean.TRUE.equals(r.get("phone_prompt")) || channels.usesGepg()
                || AggregatorProvider.SIMULATED.name().equals(r.get("provider")) && !rehearsalAllowed()) {
            return new PaymentChannelService.PhonePrompt(false, false, null);
        }
        AggregatorProvider p = AggregatorProvider.valueOf((String) r.get("provider"));
        return new PaymentChannelService.PhonePrompt(true, p.needsNetwork(), p.label());
    }

    /** The account whatever its state (for a test or a notification that arrives after the account was switched off). */
    @Transactional(readOnly = true)
    public Optional<AggregatorClient.Account> load() {
        Map<String, Object> r = row();
        return r == null ? Optional.empty() : Optional.of(account(r));
    }

    /** Whether a notification came to the institution's own address. */
    @Transactional(readOnly = true)
    public boolean tokenMatches(AggregatorProvider provider, String token) {
        Map<String, Object> r = row();
        if (r == null || token == null || !provider.name().equals(r.get("provider")) || r.get("notify_token_enc") == null) return false;
        String mine = cipher.decrypt((String) r.get("notify_token_enc"));
        return java.security.MessageDigest.isEqual(mine.getBytes(java.nio.charset.StandardCharsets.UTF_8), token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private AggregatorClient.Account account(Map<String, Object> r) {
        AggregatorProvider p = AggregatorProvider.valueOf((String) r.get("provider"));
        boolean live = "LIVE".equals(r.get("environment"));
        Map<String, String> fields = new HashMap<>(json((String) r.get("settings")));
        fields.putAll(secrets(r));
        String base = r.get("base_url") != null ? (String) r.get("base_url") : p.defaultUrl(live);
        String auth = r.get("auth_url") != null ? (String) r.get("auth_url") : p.defaultAuthUrl(live);
        return new AggregatorClient.Account(p, live, fields, base, auth, notifyUrl(p, r));
    }

    private String notifyUrl(AggregatorProvider p, Map<String, Object> r) {
        if (r.get("notify_token_enc") == null) return null;
        return links.url("/api/integrations/collections/" + p.name().toLowerCase() + "/" + cipher.decrypt((String) r.get("notify_token_enc")));
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> l = jdbc.queryForList("select * from collection_accounts");
        return l.isEmpty() ? null : l.get(0);
    }

    private Map<String, String> secrets(Map<String, Object> r) {
        String enc = (String) r.get("secrets_enc");
        return enc == null ? Map.of() : json(cipher.decrypt(enc));
    }

    private static Map<String, String> json(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return AggregatorHttp.JSON.readValue(s, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toJson(Map<String, String> m) {
        try {
            return AggregatorHttp.JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
