package tz.co.msingi.cims.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends sessions the administrator has taken back: a disabled or locked account, changed roles or a reset password stop
 * the user's existing tokens at once (within {@link #TTL_MS} on other servers): each token carries the account's session
 * generation and older generations are refused. While a temporary password is in use the
 * token only allows changing it.
 */
@Component
public class SessionGuard extends OncePerRequestFilter {

    static final long TTL_MS = 30_000;

    private record State(String status, Instant validAfter, long fetchedAt) {
    }

    private final Map<String, State> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SessionGuard(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Forget what is known about a user, so the next request reads their account again (called after a change). */
    public void evict(Long tenantId, Long userId) {
        cache.remove(tenantId + ":" + userId);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
            var jwt = auth.getToken();
            String path = req.getRequestURI().substring(req.getContextPath().length());
            boolean platformPath = path.startsWith("/api/platform/");
            boolean platformToken = CurrentUser.isPlatform(auth);
            // a platform token works only on the platform console, and an institution token never does
            if (platformToken != platformPath) {
                deny(res, HttpServletResponse.SC_FORBIDDEN, "WRONG_REALM", platformToken
                        ? "A Msingi platform sign-in cannot be used in an institution. Sign in to the institution instead."
                        : "Sign in to the Msingi platform console to use this.");
                return;
            }
            Object uid = jwt.getClaims().get(TokenService.CLAIM_USER_ID);
            long generation = jwt.getClaims().get(TokenService.CLAIM_SESSION) instanceof Number g ? g.longValue() : 0L;
            State st = null;
            boolean check = false;
            if (platformToken && uid instanceof Number n) {
                st = platformState(n.longValue());
                check = true;
            } else if (TenantContext.current().isPresent() && uid instanceof Number n) {
                st = state(TenantContext.current().get().id(), n.longValue());
                check = true;
            }
            if (check) {
                if (st == null || !"ACTIVE".equals(st.status())
                        || (st.validAfter() != null && generation < st.validAfter().toEpochMilli())) {
                    deny(res, HttpServletResponse.SC_UNAUTHORIZED, "SESSION_ENDED",
                            "Your session has ended. Sign in again.");
                    return;
                }
                String passwordPath = platformToken ? "/api/platform/auth/" : "/api/auth/";
                if (Boolean.TRUE.equals(jwt.getClaims().get(TokenService.CLAIM_MUST_CHANGE_PASSWORD))
                        && !path.startsWith(passwordPath)) {
                    deny(res, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                            "Choose your own password before continuing.");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** Forget what is known about a platform user (after a change to their account). */
    public void evictPlatform(Long userId) {
        cache.remove("platform:" + userId);
    }

    private State platformState(long userId) {
        String key = "platform:" + userId;
        State cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < TTL_MS) {
            return cached;
        }
        List<State> rows = jdbc.query("select status, sessions_valid_after from platform_users where id = ?",
                (rs, i) -> {
                    OffsetDateTime va = rs.getObject(2, OffsetDateTime.class);
                    return new State(rs.getString(1), va == null ? null : va.toInstant(), now);
                }, userId);
        State fresh = rows.isEmpty() ? null : rows.get(0);
        if (fresh != null) {
            cache.put(key, fresh);
        }
        return fresh;
    }

    private State state(Long tenantId, long userId) {
        String key = tenantId + ":" + userId;
        State cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < TTL_MS) {
            return cached;
        }
        State fresh = tx.execute(s -> {
            List<State> rows = jdbc.query("select status, sessions_valid_after from user_accounts where id = ?",
                    (rs, i) -> {
                        OffsetDateTime va = rs.getObject(2, OffsetDateTime.class);
                        return new State(rs.getString(1), va == null ? null : va.toInstant(), now);
                    }, userId);
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (fresh != null) {
            cache.put(key, fresh);
        }
        return fresh;
    }

    private static void deny(HttpServletResponse res, int status, String code, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"status\":" + status + ",\"title\":\"" + (status == 401 ? "Unauthorized" : "Forbidden")
                + "\",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
