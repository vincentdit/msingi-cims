package tz.co.msingi.cims.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The institution's NHIF credentials and the callback credentials it gives NHIF (the institution administrator). */
@Tag(name = "NHIF set-up")
@RestController
@RequestMapping("/api/nhif-setup")
@RequiredArgsConstructor
public class NhifSetupController {

    private static final String ADMIN = "hasRole('INSTITUTION_ADMIN')";

    private final NhifSetupService service;

    @Operation(summary = "The institution's NHIF set-up (the secret is never returned)")
    @GetMapping
    @PreAuthorize(ADMIN + " or hasRole('REGISTRAR')")
    public NhifSetupService.Setup view() {
        return service.view();
    }

    @Operation(summary = "Save the client id, secret (empty keeps the current one), scope, SystemId, SubSystemId and education level NHIF issued")
    @PutMapping
    @PreAuthorize(ADMIN)
    public NhifSetupService.Setup save(@Valid @RequestBody NhifSetupService.SetupRequest req) {
        return service.save(req);
    }

    @Operation(summary = "Make new credentials for NHIF's callbacks (the secret is shown this once; earlier credentials stop working)")
    @PostMapping("/callback-credentials")
    @PreAuthorize(ADMIN)
    public NhifSetupService.CallbackCredentials callbackCredentials() {
        return service.newCallbackCredentials();
    }
}
