package tz.co.msingi.cims.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/** Convenience access to the authenticated user from the bearer token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The signed-in institution user's id; empty for Msingi platform staff, whose ids belong to another table. */
    public static Optional<Long> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && !isPlatform(jwt)) {
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

    /** The signed-in person's name. Msingi platform staff acting on an institution are shown as such in its records. */
    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "system";
        if (auth instanceof JwtAuthenticationToken jwt && isPlatform(jwt)) return auth.getName() + " (Msingi platform)";
        return auth.getName();
    }

    /** The signed-in Msingi platform staff member's id (platform console only). */
    public static Optional<Long> platformId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && isPlatform(jwt)
                && jwt.getToken().getClaims().get(TokenService.CLAIM_USER_ID) instanceof Number n) {
            return Optional.of(n.longValue());
        }
        return Optional.empty();
    }

    /** The signed-in Msingi platform staff member's own name, without the "(Msingi platform)" mark. */
    public static String platformName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    public static boolean isPlatform(JwtAuthenticationToken jwt) {
        return TokenService.TYPE_PLATFORM.equals(jwt.getToken().getClaimAsString(TokenService.CLAIM_USER_TYPE));
    }
}
