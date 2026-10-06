package tz.co.msingi.cims.platform.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

/**
 * Rejects a token issued for one institution when it is presented to another.
 * Tokens must carry a "tenant" claim equal to the institution resolved for the request.
 */
public class TenantClaimValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error WRONG_TENANT =
            new OAuth2Error("invalid_token", "Token was issued for a different institution", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String claim = jwt.getClaimAsString(TokenService.CLAIM_TENANT);
        return TenantContext.current()
                .filter(t -> t.code().equalsIgnoreCase(claim))
                .map(t -> OAuth2TokenValidatorResult.success())
                .orElse(OAuth2TokenValidatorResult.failure(WRONG_TENANT));
    }
}
