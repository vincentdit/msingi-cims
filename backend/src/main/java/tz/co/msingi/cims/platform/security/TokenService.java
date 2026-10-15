package tz.co.msingi.cims.platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.time.Instant;
import java.util.List;

/** Issues access tokens in local security mode. */
@Service
@RequiredArgsConstructor
public class TokenService {

    public static final String CLAIM_TENANT = "tenant";
    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_USER_TYPE = "typ";
    public static final String CLAIM_NAME = "name";
    /** Present (true) while the user must replace a temporary password; only the password change is allowed until then. */
    public static final String CLAIM_MUST_CHANGE_PASSWORD = "mcp";
    /** The institution requires MFA for this staff member and it is not set up yet: the token only allows setting it up. */
    public static final String CLAIM_MFA_SETUP = "msu";
    /** The account's session generation when the token was issued; older generations are refused once sessions are ended. */
    public static final String CLAIM_SESSION = "sv";
    /** The user type of Msingi platform staff; their tokens carry no institution and work only on /api/platform. */
    public static final String TYPE_PLATFORM = "PLATFORM";
    /** Appended to the issuer for platform tokens, so they are always checked with CIMS's own key. */
    public static final String PLATFORM_ISSUER_SUFFIX = "-platform";

    private final JwtEncoder encoder;
    private final CimsSecurityProperties props;
    private final tz.co.msingi.cims.platform.security.mfa.MfaPolicy mfaPolicy;

    public TokenResponse issue(UserAccount user) {
        TenantContext.TenantInfo tenant = TenantContext.require();
        Instant now = Instant.now();
        Instant expires = now.plus(props.accessTokenTtl());
        List<String> roles = user.getRoles().stream().map(r -> r.getRole().name()).sorted().toList();
        boolean mfaSetup = user.getUserType() == UserAccount.UserType.STAFF && !tz.co.msingi.cims.platform.security.mfa.MfaPolicy.enabled(user)
                && mfaPolicy.required(user);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getPublicId().toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim(CLAIM_TENANT, tenant.code())
                .claim(CLAIM_TENANT_ID, tenant.id())
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_USER_TYPE, user.getUserType().name())
                .claim(CLAIM_NAME, user.getFullName())
                .claim(CLAIM_MUST_CHANGE_PASSWORD, user.isMustChangePassword())
                .claim(CLAIM_MFA_SETUP, mfaSetup)
                .claim(CLAIM_SESSION, user.getSessionsValidAfter() == null ? 0L : user.getSessionsValidAfter().toInstant().toEpochMilli())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", props.accessTokenTtl().toSeconds(),
                new TokenResponse.UserSummary(user.getId(), user.getFullName(), user.getUserType().name(), roles, tenant.code(), user.isMustChangePassword(),
                        mfaSetup, user.getLanguage()), null);
    }

    /** After the password, when the staff member uses MFA: no token yet, only a challenge to send back with the code. */
    public static TokenResponse mfaChallenge(String challenge, boolean sms) {
        return new TokenResponse(null, null, 0, null, new MfaChallenge(challenge, sms));
    }

    /** mfa: set when a second step is needed (accessToken is then null). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserSummary user, MfaChallenge mfa) {
        public TokenResponse(String accessToken, String tokenType, long expiresIn, UserSummary user) {
            this(accessToken, tokenType, expiresIn, user, null);
        }

        /** language: en or sw as the person chose, or null (BR-40). */
        public record UserSummary(Long id, String fullName, String userType, List<String> roles, String tenant, boolean mustChangePassword,
                                  boolean mfaSetupRequired, String language) {
            public UserSummary(Long id, String fullName, String userType, List<String> roles, String tenant, boolean mustChangePassword) {
                this(id, fullName, userType, roles, tenant, mustChangePassword, false, null);
            }

            public UserSummary(Long id, String fullName, String userType, List<String> roles, String tenant, boolean mustChangePassword,
                               boolean mfaSetupRequired) {
                this(id, fullName, userType, roles, tenant, mustChangePassword, mfaSetupRequired, null);
            }
        }
    }

    public record MfaChallenge(String challenge, boolean smsAvailable) {
    }
}
