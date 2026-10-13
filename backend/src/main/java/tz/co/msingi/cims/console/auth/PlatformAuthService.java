package tz.co.msingi.cims.console.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
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
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.Me;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.TokenResponse;
import tz.co.msingi.cims.platform.security.CimsSecurityProperties;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.SessionGuard;
import tz.co.msingi.cims.platform.security.TokenService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Sign-in for Msingi platform staff, with the same lock-out as institution accounts. Platform tokens carry no institution,
 * are issued by CIMS itself (issuer "…-platform") and work only on /api/platform.
 */
@Service
@RequiredArgsConstructor
public class PlatformAuthService {

    private static final String GENERIC_ERROR = "Invalid username or password";

    private final PlatformUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final CimsSecurityProperties props;
    private final PlatformAuditService audit;
    private final SessionGuard guard;

    @Transactional(noRollbackFor = {BadCredentialsException.class, LockedException.class})
    public TokenResponse login(String username, String password) {
        PlatformUser u = users.findByUsernameIgnoreCase(username.trim()).orElseThrow(() -> new BadCredentialsException(GENERIC_ERROR));
        if (u.getStatus() == PlatformUser.Status.LOCKED) {
            audit.recordAs(u.getId(), u.getFullName(), "LOGIN_FAILED", null, "PlatformUser", u.getId(), Map.of("reason", "account locked"));
            throw new LockedException("Account locked after repeated failed sign-ins. Ask another platform administrator to reset it.");
        }
        if (u.getStatus() == PlatformUser.Status.DISABLED) {
            audit.recordAs(u.getId(), u.getFullName(), "LOGIN_FAILED", null, "PlatformUser", u.getId(), Map.of("reason", "account disabled"));
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (!encoder.matches(password, u.getPasswordHash())) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            audit.recordAs(u.getId(), u.getFullName(), "LOGIN_FAILED", null, "PlatformUser", u.getId(),
                    Map.of("reason", "wrong password", "failedLogins", u.getFailedLogins()));
            if (u.getFailedLogins() >= props.maxFailedLogins()) {
                u.setStatus(PlatformUser.Status.LOCKED);
                audit.recordAs(u.getId(), u.getFullName(), "ACCOUNT_LOCKED", null, "PlatformUser", u.getId(), Map.of("failedLogins", u.getFailedLogins()));
            }
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        u.setFailedLogins(0);
        u.setLastLoginAt(OffsetDateTime.now());
        audit.recordAs(u.getId(), u.getFullName(), "LOGIN", null, "PlatformUser", u.getId(), null);
        return issue(u);
    }

    @Transactional(readOnly = true)
    public Me me() {
        return toMe(current());
    }

    /** The signed-in platform user replaces their password; their other sessions end. */
    @Transactional
    public TokenResponse changePassword(String current, String next) {
        PlatformUser u = current();
        if (!encoder.matches(current, u.getPasswordHash())) {
            throw new BusinessRuleException("WRONG_PASSWORD", "Your current password is not correct.");
        }
        if (encoder.matches(next, u.getPasswordHash())) {
            throw new BusinessRuleException("SAME_PASSWORD", "Choose a password different from the current one.");
        }
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        u.setSessionsValidAfter(OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        users.saveAndFlush(u);
        guard.evictPlatform(u.getId());
        audit.record("PASSWORD_CHANGED", null, "PlatformUser", u.getId(), null);
        return issue(u);
    }

    TokenResponse issue(PlatformUser u) {
        Instant now = Instant.now();
        Instant expires = now.plus(props.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer() + TokenService.PLATFORM_ISSUER_SUFFIX)
                .subject("platform:" + u.getId())
                .issuedAt(now)
                .expiresAt(expires)
                .claim(TokenService.CLAIM_USER_ID, u.getId())
                .claim(TokenService.CLAIM_ROLES, List.of(Role.PLATFORM_ADMIN.name()))
                .claim(TokenService.CLAIM_USER_TYPE, TokenService.TYPE_PLATFORM)
                .claim(TokenService.CLAIM_NAME, u.getFullName())
                .claim(TokenService.CLAIM_MUST_CHANGE_PASSWORD, u.isMustChangePassword())
                .claim(TokenService.CLAIM_SESSION, u.getSessionsValidAfter() == null ? 0L : u.getSessionsValidAfter().toInstant().toEpochMilli())
                .build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", props.accessTokenTtl().toSeconds(), toMe(u));
    }

    private PlatformUser current() {
        Long id = CurrentUser.platformId().orElseThrow(() -> new NotFoundException("Platform account not found"));
        return users.findById(id).orElseThrow(() -> new NotFoundException("Platform account not found"));
    }

    private static Me toMe(PlatformUser u) {
        return new Me(u.getId(), u.getUsername(), u.getFullName(), u.isMustChangePassword());
    }
}
