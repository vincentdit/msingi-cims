package tz.co.msingi.cims.integration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CimsSecurityProperties;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Other systems the institution connects (an LMS, the accounting package, a library system) reach CIMS with their own
 * client credentials (OAuth2 client credentials grant), limited to the scopes the administrator granted (BR-41 / FR-INT-09).
 */
@Service
@RequiredArgsConstructor
public class ApiClientService {

    /** What a client may be allowed to read. */
    public static final Map<String, String> SCOPES = new LinkedHashMap<>();

    static {
        SCOPES.put("programmes:read", "Programmes offered");
        SCOPES.put("students:read", "Student register (names, programme, year, status, contacts)");
        SCOPES.put("results:read", "Published semester results");
        SCOPES.put("payments:read", "Fee payments received");
    }

    public static final String TYPE_API = "API";
    public static final String CLAIM_CLIENT = "cid";
    public static final String CLAIM_SCOPES = "scp";
    static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final CimsSecurityProperties props;
    private final AuditService audit;
    private final tz.co.msingi.cims.platform.security.SessionGuard sessions;
    private final SecureRandom random = new SecureRandom();
    private volatile String dummyHash;

    public record ClientView(Long id, String clientId, String name, List<String> scopes, boolean active, String createdBy,
                             OffsetDateTime lastUsedAt, OffsetDateTime createdAt) {
    }

    public record ClientRequest(@NotBlank @Size(max = 150) String name, @NotEmpty List<String> scopes) {
    }

    /** The secret is shown once, when the client is created or its secret is replaced. */
    public record IssuedSecret(ClientView client, String clientSecret) {
    }

    public record Token(String access_token, String token_type, long expires_in, String scope) {
    }

    @Transactional(readOnly = true)
    public List<ClientView> list() {
        return jdbc.query("select id, client_id, name, scopes, active, created_by, last_used_at, created_at from api_clients order by name",
                (rs, i) -> new ClientView(rs.getLong(1), rs.getString(2), rs.getString(3), List.of(rs.getString(4).split(" ")),
                        rs.getBoolean(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class)));
    }

    @Transactional
    public IssuedSecret create(ClientRequest r) {
        String scopes = checkScopes(r.scopes());
        String clientId = TenantContext.require().code().toLowerCase(Locale.ROOT) + "-" + hex(5);
        String secret = secret();
        Long id = jdbc.queryForObject("""
                insert into api_clients (tenant_id, client_id, name, secret_hash, scopes, created_by)
                values (cims_current_tenant(), ?, ?, ?, ?, ?) returning id""", Long.class,
                clientId, r.name().trim(), encoder.encode(secret), scopes, CurrentUser.name());
        audit.record("API_CLIENT_CREATED", "ApiClient", id, Map.of("clientId", clientId, "scopes", scopes));
        return new IssuedSecret(get(id), secret);
    }

    @Transactional
    public ClientView update(Long id, ClientRequest r, boolean active) {
        get(id);
        String scopes = checkScopes(r.scopes());
        jdbc.update("update api_clients set name = ?, scopes = ?, active = ?, updated_at = now(), version = version + 1 where id = ?",
                r.name().trim(), scopes, active, id);
        sessions.evictClient(TenantContext.require().id(), id);
        audit.record("API_CLIENT_UPDATED", "ApiClient", id, Map.of("scopes", scopes, "active", active));
        return get(id);
    }

    @Transactional
    public IssuedSecret rotate(Long id) {
        get(id);
        String secret = secret();
        jdbc.update("update api_clients set secret_hash = ?, updated_at = now(), version = version + 1 where id = ?", encoder.encode(secret), id);
        audit.record("API_CLIENT_SECRET_REPLACED", "ApiClient", id, Map.of());
        return new IssuedSecret(get(id), secret);
    }

    /** The client credentials grant. Errors are deliberately the same for an unknown client and a wrong secret. */
    @Transactional
    public Token token(String clientId, String clientSecret, String requestedScope) {
        if (clientId == null || clientSecret == null) throw new InvalidClient();
        String prefix = TenantContext.require().code().toLowerCase(Locale.ROOT) + "-";
        if (!clientId.startsWith(prefix)) throw new InvalidClient();
        List<Map<String, Object>> rows = jdbc.queryForList("select id, name, secret_hash, scopes, active from api_clients where client_id = ?", clientId);
        if (rows.isEmpty()) {
            if (dummyHash == null) dummyHash = encoder.encode("not-a-client");
            encoder.matches(clientSecret, dummyHash); // the same work either way, so client ids cannot be probed by timing
            throw new InvalidClient();
        }
        Map<String, Object> c = rows.get(0);
        Long id = ((Number) c.get("id")).longValue();
        if (!Boolean.TRUE.equals(c.get("active")) || !encoder.matches(clientSecret, (String) c.get("secret_hash"))) {
            audit.recordAs(null, clientId, "API_CLIENT_TOKEN_REFUSED", "ApiClient", id, Map.of());
            throw new InvalidClient();
        }
        Set<String> granted = new LinkedHashSet<>(List.of(((String) c.get("scopes")).split(" ")));
        Set<String> scopes = granted;
        if (requestedScope != null && !requestedScope.isBlank()) {
            scopes = new LinkedHashSet<>(List.of(requestedScope.trim().split("\\s+")));
            if (!granted.containsAll(scopes)) throw new InvalidScope();
        }
        jdbc.update("update api_clients set last_used_at = now() where id = ?", id);
        TenantContext.TenantInfo t = TenantContext.require();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject("client:" + clientId)
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .claim(TokenService.CLAIM_TENANT, t.code())
                .claim(TokenService.CLAIM_TENANT_ID, t.id())
                .claim(TokenService.CLAIM_ROLES, List.of())
                .claim(TokenService.CLAIM_USER_TYPE, TYPE_API)
                .claim(TokenService.CLAIM_NAME, "API: " + c.get("name"))
                .claim(CLAIM_CLIENT, id)
                .claim(CLAIM_SCOPES, List.copyOf(scopes))
                .build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new Token(token, "Bearer", TOKEN_TTL.toSeconds(), String.join(" ", scopes));
    }

    /** Is the client still allowed in (read on every API call, cached briefly by the session guard)? */
    @Transactional(readOnly = true)
    public boolean active(long id) {
        List<Boolean> r = jdbc.queryForList("select active from api_clients where id = ?", Boolean.class, id);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    private ClientView get(Long id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("ApiClient", id));
    }

    private static String checkScopes(List<String> scopes) {
        for (String s : scopes) {
            if (!SCOPES.containsKey(s)) throw new BusinessRuleException("UNKNOWN_SCOPE", "Unknown scope: " + s);
        }
        return String.join(" ", new LinkedHashSet<>(scopes));
    }

    private String secret() {
        byte[] b = new byte[30];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static class InvalidClient extends RuntimeException {
    }

    public static class InvalidScope extends RuntimeException {
    }
}
