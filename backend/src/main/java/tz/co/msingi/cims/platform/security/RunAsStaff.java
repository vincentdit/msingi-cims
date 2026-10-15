package tz.co.msingi.cims.platform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs background work (a scheduled report) with the authority of the staff member who set it up, so the same role and data-scope
 * checks apply as when they run it themselves. The current tenant must already be set.
 */
public final class RunAsStaff {

    private RunAsStaff() {
    }

    public static <T> T call(UserAccount user, Supplier<T> work) {
        TenantContext.TenantInfo tenant = TenantContext.require();
        List<String> roles = user.getRoles().stream().map(r -> r.getRole().name()).distinct().toList();
        Jwt jwt = Jwt.withTokenValue("background").header("alg", "none").subject(user.getPublicId().toString())
                .claim(TokenService.CLAIM_TENANT, tenant.code()).claim(TokenService.CLAIM_TENANT_ID, tenant.id())
                .claim(TokenService.CLAIM_USER_ID, user.getId()).claim(TokenService.CLAIM_ROLES, roles)
                .claim(TokenService.CLAIM_USER_TYPE, user.getUserType().name()).claim(TokenService.CLAIM_NAME, user.getFullName())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        List<GrantedAuthority> authorities = roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new JwtAuthenticationToken(jwt, authorities, user.getFullName()));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
