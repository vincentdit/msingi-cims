package tz.co.msingi.cims.platform.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Security settings")
@RestController
@RequestMapping("/api/security-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INSTITUTION_ADMIN')")
public class SecuritySettingsController {

    private final SecuritySettingsService service;

    @Operation(summary = "MFA policy, idle sign-out and retention periods")
    @GetMapping
    public SecuritySettingsService.Settings get() {
        return service.get();
    }

    @PutMapping
    public SecuritySettingsService.Settings save(@Valid @RequestBody SecuritySettingsService.Settings s) {
        return service.save(s);
    }
}
