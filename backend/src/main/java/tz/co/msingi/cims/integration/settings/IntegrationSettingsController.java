package tz.co.msingi.cims.integration.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Integration settings")
@RestController
@RequestMapping("/api/integration-settings")
@RequiredArgsConstructor
public class IntegrationSettingsController {

    private final IntegrationSettingsService service;

    @Operation(summary = "The institution's GePG, HESLB, NIDA, MUSE and TAMISEMI settings, and which systems are live")
    @GetMapping
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT', 'REGISTRAR', 'ADMISSIONS_OFFICER', 'EXAMINATIONS_OFFICER')")
    public IntegrationSettingsService.View get() {
        return service.view();
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN', 'BURSAR')")
    public IntegrationSettingsService.View save(@Valid @RequestBody IntegrationSettingsService.Settings s) {
        return service.save(s);
    }
}
