package tz.co.msingi.cims.console.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.finance.aggregator.AggregatorClient;
import tz.co.msingi.cims.finance.aggregator.AggregatorClients;
import tz.co.msingi.cims.finance.aggregator.AggregatorProvider;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * Msingi's own merchant account with a payment aggregator (console › Billing › Payment collection): institutions paying a Msingi
 * invoice (an SMS bundle, a licence) can have their phone prompted, and the money goes to Msingi. Credentials are encrypted and
 * never shown again; notifications are believed only at Msingi's own address, with its secret part.
 */
@Service
@RequiredArgsConstructor
public class MsingiCollectionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record FieldView(String name, String label, boolean secret, String value, boolean set) {
    }

    public record ProviderView(String code, String label, List<AggregatorProvider.Field> fields, String sandboxUrl, String liveUrl, boolean needsNetwork) {
    }

    public record View(String provider, String providerLabel, String environment, boolean active, List<FieldView> fields, String baseUrl, String authUrl,
                       String notifyUrl, OffsetDateTime lastTestAt, Boolean lastTestOk, String lastTestNote, List<ProviderView> providers,
                       boolean rehearsalAllowed, String billPayNamba, String c2bUrl, String c2bToken) {
    }

    /** Paying by reference: ClickPesa BillPay (the namba before the reference) or Selcom Pay (merchant number, then the reference). */
    public record PayRef(AggregatorProvider provider, String number) {
    }

    public record SaveRequest(@NotNull AggregatorProvider provider, @NotNull String environment, boolean active, Map<String, String> fields,
                              @Size(max = 200) String baseUrl, @Size(max = 200) String authUrl, String billPayNamba) {
    }

    /** Whether payers can be prompted on their phone, whether they must name their network, and through whom. */
    public record Prompt(boolean enabled, boolean needsNetwork, String provider) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final PlatformAuditService audit;
    private final AggregatorClients clients;
    private final SecureRandom random = new SecureRandom();

    private final tz.co.msingi.cims.finance.collection.CollectionProperties collectionProps;
    private final org.springframework.core.env.Environment env;
    @Value("${cims.sms.callback-base-url:https://app.cims.ac.tz}")
    private String platformBase;

    /** The rehearsal records payments without money: only on a test system, or while CIMS_ALLOW_PAYMENT_SIMULATION is on. */
    public boolean rehearsalAllowed() {
        return collectionProps.isSimulated() || Boolean.parseBoolean(env.getProperty("cims.payments.allow-simulation", "false"));
    }

    @Transactional(readOnly = true)
    public View view() {
        boolean allowSimulation = rehearsalAllowed();
        List<ProviderView> providers = new ArrayList<>();
        for (AggregatorProvider p : AggregatorProvider.values()) {
            providers.add(new ProviderView(p.name(), p.label(), p.fields(), p.defaultUrl(false), p.defaultUrl(true), p.needsNetwork()));
        }
        Map<String, Object> r = row();
        String prov = (String) r.get("provider");
        if (prov == null) {
            return new View(null, null, (String) r.get("environment"), false, List.of(), null, null, null, null, null, null, providers, allowSimulation, null, null, null);
        }
        AggregatorProvider p = AggregatorProvider.valueOf(prov);
        Map<String, String> plain = json((String) r.get("settings"));
        Map<String, String> secrets = secrets(r);
        List<FieldView> fields = p.fields().stream().map(f -> new FieldView(f.name(), f.label(), f.secret(), f.secret() ? null : plain.get(f.name()),
                f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).toList();
        return new View(p.name(), p.label(), (String) r.get("environment"), Boolean.TRUE.equals(r.get("active")), fields, (String) r.get("base_url"),
                (String) r.get("auth_url"), notifyUrl(p, r), time(r.get("last_test_at")), (Boolean) r.get("last_test_ok"), (String) r.get("last_test_note"),
                providers, allowSimulation, (String) r.get("billpay_namba"),
                p == AggregatorProvider.SELCOM ? platformBase.replaceAll("/+$", "") + "/api/integrations/msingi-collections/selcom-c2b" : null,
                p == AggregatorProvider.SELCOM && r.get("notify_token_enc") != null ? cipher.decrypt((String) r.get("notify_token_enc")) : null);
    }

    @Transactional
    public View save(SaveRequest req) {
        if (req.provider() == AggregatorProvider.SIMULATED && req.active() && !rehearsalAllowed()) {
            throw new BusinessRuleException("REHEARSAL_NOT_ALLOWED", "The rehearsal records payments without money: only while test payments are allowed on this server.");
        }
        String environment = "LIVE".equalsIgnoreCase(req.environment()) ? "LIVE" : "SANDBOX";
        String namba = req.billPayNamba() == null || req.billPayNamba().isBlank() ? null : req.billPayNamba().trim();
        if (namba != null && req.provider() == AggregatorProvider.SELCOM && !namba.matches("\\d{4,12}")) {
            throw new BusinessRuleException("BILLPAY_NAMBA", "The Selcom Pay merchant number is the 4 to 12 digits Selcom gives Msingi.");
        }
        if (namba != null && (req.provider() == AggregatorProvider.CLICKPESA || req.provider() == AggregatorProvider.SIMULATED) && !namba.matches("\\d{4}")) {
            throw new BusinessRuleException("BILLPAY_NAMBA", "The BillPay-Namba is the 4 digits ClickPesa gives Msingi.");
        }
        if (namba != null && req.provider() == AggregatorProvider.AZAMPAY) {
            throw new BusinessRuleException("BILLPAY_NAMBA", "Paying by reference comes with Selcom (Selcom Pay) or ClickPesa (BillPay).");
        }
        Map<String, Object> r = row();
        boolean same = req.provider().name().equals(r.get("provider"));
        Map<String, String> plain = new LinkedHashMap<>();
        Map<String, String> secrets = same ? new LinkedHashMap<>(secrets(r)) : new LinkedHashMap<>();
        Map<String, String> given = req.fields() == null ? Map.of() : req.fields();
        for (AggregatorProvider.Field f : req.provider().fields()) {
            String v = given.get(f.name());
            if (v == null || v.isBlank()) continue;
            if (f.secret()) secrets.put(f.name(), v.trim());
            else plain.put(f.name(), v.trim());
        }
        if (req.active() && req.provider() != AggregatorProvider.SIMULATED) {
            List<String> missing = req.provider().fields().stream()
                    .filter(f -> !(f.secret() ? secrets.containsKey(f.name()) : plain.containsKey(f.name()))).map(AggregatorProvider.Field::label).toList();
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("AGGREGATOR_INCOMPLETE", req.provider().label() + " cannot be switched on without: " + String.join(", ", missing) + ".");
            }
        }
        jdbc.update("""
                update platform_collection_account set provider = ?, environment = ?, active = ?, settings = ?, secrets_enc = ?, base_url = ?, auth_url = ?,
                  notify_token_enc = coalesce(notify_token_enc, ?), updated_by_name = ?, updated_at = now(),
                  last_test_at = case when ? then last_test_at end, last_test_ok = case when ? then last_test_ok end,
                  last_test_note = case when ? then last_test_note end
                where id = 1""",
                req.provider().name(), environment, req.active(), toJson(plain), secrets.isEmpty() ? null : cipher.encrypt(toJson(secrets)),
                blank(req.baseUrl()), blank(req.authUrl()), cipher.encrypt(newToken()), CurrentUser.platformName(), same, same, same);
        jdbc.update("update platform_collection_account set billpay_namba = ? where id = 1", namba);
        audit.record("MSINGI_COLLECTION_UPDATED", null, "PlatformCollectionAccount", 1L,
                Map.of("provider", req.provider().name(), "environment", environment, "active", req.active()));
        return view();
    }

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
        jdbc.update("update platform_collection_account set last_test_at = now(), last_test_ok = ?, last_test_note = ? where id = 1", ok,
                note == null ? null : note.length() > 300 ? note.substring(0, 300) : note);
        return view();
    }

    @Transactional
    public View newNotifyAddress() {
        jdbc.update("update platform_collection_account set notify_token_enc = ?, updated_at = now() where id = 1", cipher.encrypt(newToken()));
        audit.record("MSINGI_COLLECTION_NOTIFY_ADDRESS_CHANGED", null, "PlatformCollectionAccount", 1L, Map.of());
        return view();
    }

    /** The account when switched on (the rehearsal only while test payments are allowed). */
    @Transactional(readOnly = true)
    public Optional<AggregatorClient.Account> active() {
        Map<String, Object> r = row();
        if (r.get("provider") == null || !Boolean.TRUE.equals(r.get("active"))) return Optional.empty();
        if (AggregatorProvider.SIMULATED.name().equals(r.get("provider")) && !rehearsalAllowed()) return Optional.empty();
        return Optional.of(account(r));
    }

    /**
     * Msingi's ClickPesa BillPay-Namba while the account is switched on: every invoice is then also payable by the BillPay control
     * number (the namba followed by its payment reference) from mobile money, CRDB SimBanking and CRDB agents.
     */
    @Transactional(readOnly = true)
    public Optional<String> billPayNamba() {
        return payRef().filter(x -> x.provider() != AggregatorProvider.SELCOM).map(PayRef::number);
    }

    /** How invoices can be paid by reference now: through which aggregator, with which number (empty when not set up). */
    @Transactional(readOnly = true)
    public Optional<PayRef> payRef() {
        return active().map(a -> new PayRef(a.provider(), (String) row().get("billpay_namba"))).filter(x -> x.number() != null && !x.number().isBlank());
    }

    /** The BillPay-Namba as saved, whether or not the account is switched on (a notification can arrive after it was switched off). */
    @Transactional(readOnly = true)
    public String savedBillPayNamba() {
        return (String) row().get("billpay_namba");
    }

    /** The invoice reference inside a BillPay control number (or the reference itself). */
    public static String referenceIn(String controlNumber, String namba) {
        String d = controlNumber == null ? "" : controlNumber.replaceAll("\\D", "");
        if (namba != null && d.length() == namba.length() + 12 && d.startsWith(namba)) return d.substring(namba.length());
        return d.length() > 12 ? d.substring(d.length() - 12) : d;
    }

    @Transactional(readOnly = true)
    public Prompt prompt() {
        return active().map(a -> new Prompt(true, a.provider().needsNetwork(), a.provider().label())).orElse(new Prompt(false, false, null));
    }

    /** The account whatever its state (a notification can arrive after it was switched off). */
    @Transactional(readOnly = true)
    public Optional<AggregatorClient.Account> load() {
        Map<String, Object> r = row();
        return r.get("provider") == null ? Optional.empty() : Optional.of(account(r));
    }

    @Transactional(readOnly = true)
    public boolean tokenMatches(AggregatorProvider provider, String token) {
        Map<String, Object> r = row();
        if (token == null || !provider.name().equals(r.get("provider")) || r.get("notify_token_enc") == null) return false;
        String mine = cipher.decrypt((String) r.get("notify_token_enc"));
        return MessageDigest.isEqual(mine.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

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
        return platformBase.replaceAll("/+$", "") + "/api/integrations/msingi-collections/" + p.name().toLowerCase() + "/"
                + cipher.decrypt((String) r.get("notify_token_enc"));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select * from platform_collection_account where id = 1");
    }

    private Map<String, String> secrets(Map<String, Object> r) {
        String enc = (String) r.get("secrets_enc");
        return enc == null ? Map.of() : json(cipher.decrypt(enc));
    }

    private static Map<String, String> json(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return JSON.readValue(s, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toJson(Map<String, String> m) {
        try {
            return JSON.writeValueAsString(m);
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
