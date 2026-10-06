package tz.co.msingi.cims.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * cims.security.* settings.
 *
 * mode=local    — CIMS issues and validates its own RS256 tokens (development, small dedicated installs).
 * mode=keycloak — tokens are issued by Keycloak (one realm per institution); CIMS validates them
 *                 against issuer-uri and reads roles from realm_access.roles.
 */
@ConfigurationProperties(prefix = "cims.security")
public record CimsSecurityProperties(
        String mode,
        String issuer,
        Duration accessTokenTtl,
        String privateKeyPem,
        String publicKeyPem,
        String keycloakIssuerUri,
        int maxFailedLogins,
        List<String> corsAllowedOrigins) {

    public CimsSecurityProperties {
        if (mode == null) mode = "local";
        if (issuer == null) issuer = "cims";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(60);
        if (maxFailedLogins <= 0) maxFailedLogins = 5;
        if (corsAllowedOrigins == null) corsAllowedOrigins = List.of();
    }

    public boolean isLocal() {
        return "local".equalsIgnoreCase(mode);
    }
}
