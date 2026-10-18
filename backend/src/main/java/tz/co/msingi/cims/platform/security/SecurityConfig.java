package tz.co.msingi.cims.platform.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/public/**",
            "/p/**",
            "/api/auth/login",
            "/api/auth/mfa/verify",
            "/api/auth/mfa/sms",
            "/api/oauth/token",
            "/api/platform/auth/login",
            "/api/applicant-auth/**",
            "/api/student-auth/**",
            "/api/integrations/gepg/**",
            "/api/integrations/collections/**",
            "/api/integrations/msingi-billing/**",
            "/api/integrations/sms/**",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionGuard sessionGuard) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(new CimsJwtConverter())))
                .addFilterAfter(sessionGuard, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    /** The session guard runs inside the security chain only, after the token is read. */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<SessionGuard> sessionGuardRegistration(SessionGuard guard) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(guard);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtKeyProvider keys) {
        RSAKey jwk = new RSAKey.Builder(keys.getPublicKey()).privateKey(keys.getPrivateKey()).keyID("cims-local").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /**
     * Institution tokens are checked against the configured issuer (CIMS itself or Keycloak) and must belong to the
     * institution of the request. Msingi platform tokens are always issued and checked by CIMS with its own key, carry no
     * institution, and are chosen by their issuer; the signature is verified either way.
     */
    @Bean
    public JwtDecoder jwtDecoder(CimsSecurityProperties props, JwtKeyProvider keys) {
        NimbusJwtDecoder decoder;
        String issuer;
        if (props.isLocal()) {
            decoder = NimbusJwtDecoder.withPublicKey(keys.getPublicKey()).build();
            issuer = props.issuer();
        } else {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(props.keycloakIssuerUri());
            issuer = props.keycloakIssuerUri();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new TenantClaimValidator()));
        String platformIssuer = props.issuer() + TokenService.PLATFORM_ISSUER_SUFFIX;
        NimbusJwtDecoder platform = NimbusJwtDecoder.withPublicKey(keys.getPublicKey()).build();
        platform.setJwtValidator(JwtValidators.createDefaultWithIssuer(platformIssuer));
        return token -> {
            String iss;
            try {
                iss = com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (java.text.ParseException e) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Malformed token", e);
            }
            return platformIssuer.equals(iss) ? platform.decode(token) : decoder.decode(token);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CimsSecurityProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(props.corsAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant", "Accept-Language"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** Maps "roles" (local tokens) or realm_access.roles (Keycloak) to ROLE_* authorities. */
    static class CimsJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList(TokenService.CLAIM_ROLES);
            if (roles == null) {
                Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
                if (realm != null && realm.get("roles") instanceof Collection<?> c) {
                    roles = c.stream().map(String::valueOf).toList();
                }
            }
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            // scopes only count on API client tokens, which carry no roles
            List<String> scopes = jwt.getClaimAsStringList(tz.co.msingi.cims.integration.api.ApiClientService.CLAIM_SCOPES);
            if (scopes != null && tz.co.msingi.cims.integration.api.ApiClientService.TYPE_API.equals(jwt.getClaimAsString(TokenService.CLAIM_USER_TYPE))) {
                scopes.forEach(sc -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + sc)));
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString(TokenService.CLAIM_NAME));
        }
    }
}
