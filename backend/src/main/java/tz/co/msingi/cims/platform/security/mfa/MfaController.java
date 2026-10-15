package tz.co.msingi.cims.platform.security.mfa;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.security.TokenService;

import java.util.List;
import java.util.Map;

/** Staff multi-factor sign-in: the second step, and setting up or removing the authenticator. */
@Tag(name = "Authentication — MFA")
@RestController
@RequiredArgsConstructor
public class MfaController {

    private final MfaService service;

    public record CodeRequest(@NotBlank @Size(max = 200) String challenge, @NotBlank @Size(max = 20) String code) {
    }

    public record ChallengeRequest(@NotBlank @Size(max = 200) String challenge) {
    }

    public record OwnCodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    public record PasswordRequest(@NotBlank String password) {
    }

    @Operation(summary = "Second sign-in step: authenticator code, recovery code or SMS code")
    @PostMapping("/api/auth/mfa/verify")
    public TokenService.TokenResponse verify(@Valid @RequestBody CodeRequest req) {
        return service.completeSignIn(req.challenge(), req.code());
    }

    @Operation(summary = "Send a sign-in code by SMS instead")
    @PostMapping("/api/auth/mfa/sms")
    public ResponseEntity<Map<String, String>> sms(@Valid @RequestBody ChallengeRequest req) {
        service.smsCode(req.challenge());
        return ResponseEntity.ok(Map.of("status", "SENT"));
    }

    @GetMapping("/api/auth/mfa")
    @PreAuthorize("isAuthenticated()")
    public MfaService.Status status() {
        return service.status();
    }

    @PostMapping("/api/auth/mfa/setup")
    @PreAuthorize("isAuthenticated()")
    public MfaService.Setup setup() {
        return service.setup();
    }

    @PostMapping("/api/auth/mfa/enable")
    @PreAuthorize("isAuthenticated()")
    public MfaService.Enabled enable(@Valid @RequestBody OwnCodeRequest req) {
        return service.enable(req.code());
    }

    @PostMapping("/api/auth/mfa/recovery-codes")
    @PreAuthorize("isAuthenticated()")
    public List<String> recovery(@Valid @RequestBody PasswordRequest req) {
        return service.regenerateRecoveryCodes(req.password());
    }

    @PostMapping("/api/auth/mfa/disable")
    @PreAuthorize("isAuthenticated()")
    public MfaService.Status disable(@Valid @RequestBody PasswordRequest req) {
        return service.disable(req.password());
    }

    @PostMapping("/api/admin/users/{id}/mfa/reset")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public ResponseEntity<Void> reset(@PathVariable Long id) {
        service.adminReset(id);
        return ResponseEntity.noContent().build();
    }
}
