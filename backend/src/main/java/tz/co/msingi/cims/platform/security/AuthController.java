package tz.co.msingi.cims.platform.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Authentication (staff)")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginService loginService;

    @Operation(summary = "Staff sign-in (local security mode)")
    @PostMapping("/login")
    public TokenService.TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return loginService.login(request.username(), request.password(), UserAccount.UserType.STAFF);
    }

    @Operation(summary = "Current user from the bearer token")
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "id", jwt.getClaims().get(TokenService.CLAIM_USER_ID),
                "name", jwt.getClaimAsString(TokenService.CLAIM_NAME),
                "userType", jwt.getClaimAsString(TokenService.CLAIM_USER_TYPE),
                "roles", jwt.getClaimAsStringList(TokenService.CLAIM_ROLES),
                "tenant", jwt.getClaimAsString(TokenService.CLAIM_TENANT));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
