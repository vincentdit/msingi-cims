package tz.co.msingi.cims.console.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.ChangePasswordRequest;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.IssuedPassword;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.LoginRequest;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.Me;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.PlatformUserRequest;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.PlatformUserView;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.TokenResponse;

import java.util.List;

/** Sign-in and accounts of Msingi platform staff. */
@Tag(name = "Platform console")
@RestController
@RequiredArgsConstructor
public class PlatformAuthController {

    static final String PLATFORM = "hasRole('PLATFORM_ADMIN')";

    private final PlatformAuthService auth;
    private final PlatformUserService users;

    @Operation(summary = "Sign in to the platform console (Msingi staff)")
    @PostMapping("/api/platform/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password());
    }

    @GetMapping("/api/platform/auth/me")
    @PreAuthorize(PLATFORM)
    public Me me() {
        return auth.me();
    }

    @PostMapping("/api/platform/auth/password")
    @PreAuthorize(PLATFORM)
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return auth.changePassword(req.currentPassword(), req.newPassword());
    }

    @GetMapping("/api/platform/users")
    @PreAuthorize(PLATFORM)
    public List<PlatformUserView> list() {
        return users.list();
    }

    @PostMapping("/api/platform/users")
    @PreAuthorize(PLATFORM)
    public IssuedPassword create(@Valid @RequestBody PlatformUserRequest req) {
        return users.create(req);
    }

    @PostMapping("/api/platform/users/{id}/reset-password")
    @PreAuthorize(PLATFORM)
    public IssuedPassword reset(@PathVariable Long id) {
        return users.resetPassword(id);
    }

    @PostMapping("/api/platform/users/{id}/disable")
    @PreAuthorize(PLATFORM)
    public PlatformUserView disable(@PathVariable Long id) {
        return users.disable(id);
    }

    @PostMapping("/api/platform/users/{id}/enable")
    @PreAuthorize(PLATFORM)
    public PlatformUserView enable(@PathVariable Long id) {
        return users.enable(id);
    }
}
