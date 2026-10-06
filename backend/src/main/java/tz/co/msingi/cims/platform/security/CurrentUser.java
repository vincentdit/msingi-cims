package tz.co.msingi.cims.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Convenience access to the authenticated user from the bearer token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<Long> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Object uid = jwt.getToken().getClaims().get(TokenService.CLAIM_USER_ID);
            if (uid instanceof Number n) {
                return Optional.of(n.longValue());
            }
        }
        return Optional.empty();
    }

    public static Long requireId() {
        return id().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
