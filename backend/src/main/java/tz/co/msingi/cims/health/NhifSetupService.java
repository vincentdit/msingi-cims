package tz.co.msingi.cims.health;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.nhif.NhifClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.links.PortalLinks;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The institution's NHIF set-up for the Student Registration API, in two directions:
 * <ul>
 *   <li><b>To NHIF</b>: the institution's credentials as an NHIF registration agent (client id, secret, scope, SystemId, SubSystemId)
 *       and NHIF's education level for its students. The secret is kept encrypted and never shown again.</li>
 *   <li><b>From NHIF</b>: the credentials NHIF uses to call the institution back. CIMS makes them (the secret is shown once and kept
 *       only as a hash); NHIF exchanges them at CIMS's token address for an hour's token, sent with each callback.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NhifSetupService {

    static final int TOKEN_SECONDS = 3600;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final PortalLinks links;
    private final IntegrationSettingsService settings;
    private final AuditService audit;
    private final Environment env;
    private final SecureRandom random = new SecureRandom();

    public record Setup(boolean enabled, String mode, String agentNo, String clientId, boolean secretSet, String scope, Integer systemId,
                        Integer subSystemId, int educationLevel, boolean complete, String callbackClientId, OffsetDateTime callbackCreatedAt,
                        String callbackUrl, String tokenUrl) {
    }

    public record SetupRequest(@Size(max = 100) String clientId, @Size(max = 200) String clientSecret, @Size(max = 200) String scope,
                               @Min(1) Integer systemId, @Min(0) Integer subSystemId, @Min(1) @Max(5) Integer educationLevel) {
    }

    /** What to give NHIF so it can call the institution back: the secret appears here once only. */
    public record CallbackCredentials(String clientId, String clientSecret, String tokenUrl, String callbackUrl) {
    }

    public record IssuedToken(String access_token, String token_type, long expires_in, String scope) {
    }

    @Transactional(readOnly = true)
    public Setup view() {
        var s = settings.get();
        Map<String, Object> r = row();
        String clientId = (String) r.get("nhif_client_id");
        Integer systemId = (Integer) r.get("nhif_system_id");
        Integer subSystemId = (Integer) r.get("nhif_sub_system_id");
        boolean secret = r.get("nhif_client_secret_enc") != null;
        boolean live = "live".equalsIgnoreCase(env.getProperty("cims.nhif.mode", "simulated"));
        Object created = r.get("nhif_callback_created_at");
        return new Setup(s.nhifEnabled(), live ? "LIVE" : "SIMULATED", s.nhifCode() != null ? s.nhifCode() : settings.institutionCode(), clientId, secret,
                (String) r.get("nhif_scope"), systemId, subSystemId, r.get("nhif_education_level") == null ? 2 : ((Number) r.get("nhif_education_level")).intValue(),
                clientId != null && secret && systemId != null && subSystemId != null, (String) r.get("nhif_callback_client_id"),
                created instanceof java.sql.Timestamp t ? t.toInstant().atOffset(java.time.ZoneOffset.UTC) : (OffsetDateTime) created,
                links.url("/api/integrations/nhif/callback"), links.url("/api/integrations/nhif/token"));
    }

    @Transactional
    public Setup save(SetupRequest req) {
        ensureRow();
        String secret = req.clientSecret() == null || req.clientSecret().isBlank() ? null : cipher.encrypt(req.clientSecret().trim());
        jdbc.update("""
                update integration_settings set nhif_client_id = ?, nhif_scope = ?, nhif_system_id = ?, nhif_sub_system_id = ?,
                  nhif_education_level = ?, nhif_client_secret_enc = coalesce(?, nhif_client_secret_enc), updated_by_name = ?, updated_at = now()""",
                blank(req.clientId()), blank(req.scope()), req.systemId(), req.subSystemId(), req.educationLevel() == null ? 2 : req.educationLevel(),
                secret, CurrentUser.name());
        audit.record("NHIF_SETUP_UPDATED", "IntegrationSettings", null, Map.of("clientId", String.valueOf(blank(req.clientId())),
                "secretChanged", secret != null, "systemId", String.valueOf(req.systemId()), "subSystemId", String.valueOf(req.subSystemId())));
        return view();
    }

    /** The institution's agent credentials, for a call to NHIF; refuses with what is missing. */
    @Transactional(readOnly = true)
    public NhifClient.Agent agent() {
        settings.nhifCode();   // NHIF switched on
        Map<String, Object> r = row();
        boolean live = "live".equalsIgnoreCase(env.getProperty("cims.nhif.mode", "simulated"));
        String id = (String) r.get("nhif_client_id");
        String enc = (String) r.get("nhif_client_secret_enc");
        Integer sys = (Integer) r.get("nhif_system_id");
        Integer sub = (Integer) r.get("nhif_sub_system_id");
        if (!live && (id == null || enc == null || sys == null || sub == null)) {
            return new NhifClient.Agent("simulated-" + settings.institutionCode(), "simulated", "student.registration", 1, 1);
        }
        if (id == null || enc == null || sys == null || sub == null) {
            throw new BusinessRuleException("NHIF_NOT_SET_UP", "NHIF is not set up for this institution: enter the client id, client secret, "
                    + "SystemId and SubSystemId NHIF issued (Integrations › NHIF).");
        }
        return new NhifClient.Agent(id, cipher.decrypt(enc), (String) r.get("nhif_scope"), sys, sub);
    }

    @Transactional(readOnly = true)
    public int educationLevel() {
        Object v = row().get("nhif_education_level");
        return v == null ? 2 : ((Number) v).intValue();
    }

    /** New credentials for NHIF's callbacks (any earlier ones stop working, and their tokens with them). */
    @Transactional
    public CallbackCredentials newCallbackCredentials() {
        ensureRow();
        String code = settings.institutionCode().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String clientId = "cims-" + (code.length() > 20 ? code.substring(0, 20) : code) + "-" + randomText(4).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "x");
        String secret = randomText(30);
        jdbc.update("update integration_settings set nhif_callback_client_id = ?, nhif_callback_secret_hash = ?, nhif_callback_created_at = now()",
                clientId, encoder.encode(secret));
        jdbc.update("delete from nhif_callback_tokens");
        audit.record("NHIF_CALLBACK_CREDENTIALS_CREATED", "IntegrationSettings", null, Map.of("clientId", clientId));
        return new CallbackCredentials(clientId, secret, links.url("/api/integrations/nhif/token"), links.url("/api/integrations/nhif/callback"));
    }

    /** NHIF exchanging the callback credentials for a token (OAuth2 client_credentials), as the API documents for agents. */
    @Transactional
    public IssuedToken issueToken(String clientId, String clientSecret, String grantType, String scope) {
        if (!"client_credentials".equals(grantType)) throw new BusinessRuleException("unsupported_grant_type", "grant_type must be client_credentials");
        Map<String, Object> r = row();
        String id = (String) r.get("nhif_callback_client_id");
        String hash = (String) r.get("nhif_callback_secret_hash");
        if (id == null || hash == null || clientId == null || clientSecret == null || !id.equals(clientId.trim()) || !encoder.matches(clientSecret, hash)) {
            throw new BusinessRuleException("invalid_client", "Unknown client or wrong secret");
        }
        var t = links.newToken();
        jdbc.update("delete from nhif_callback_tokens where expires_at < now()");
        jdbc.update("insert into nhif_callback_tokens (tenant_id, token_hash, expires_at) values (cims_current_tenant(), ?, now() + make_interval(secs => ?))",
                t.hash(), TOKEN_SECONDS);
        return new IssuedToken(t.token(), "Bearer", TOKEN_SECONDS, scope == null ? "nhif.callback" : scope);
    }

    /** Whether a callback carries a token CIMS issued to NHIF for this institution and that has not expired. */
    @Transactional(readOnly = true)
    public boolean validToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) return false;
        Integer n = jdbc.queryForObject("select count(*) from nhif_callback_tokens where token_hash = ? and expires_at > now()", Integer.class,
                PortalLinks.hash(token));
        return n != null && n > 0;
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> l = jdbc.queryForList("""
                select nhif_client_id, nhif_client_secret_enc, nhif_scope, nhif_system_id, nhif_sub_system_id, nhif_education_level,
                       nhif_callback_client_id, nhif_callback_secret_hash, nhif_callback_created_at from integration_settings""");
        return l.isEmpty() ? new java.util.HashMap<>() : l.get(0);
    }

    private void ensureRow() {
        Integer n = jdbc.queryForObject("select count(*) from integration_settings", Integer.class);
        if (n == null || n == 0) {
            jdbc.update("insert into integration_settings (tenant_id, updated_by_name) values (cims_current_tenant(), ?)", CurrentUser.name());
        }
    }

    private String randomText(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
