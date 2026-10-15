package tz.co.msingi.cims.platform.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.util.List;
import java.util.Map;

@Tag(name = "Privacy")
@RestController
@RequiredArgsConstructor
public class PrivacyController {

    private static final String HANDLERS = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private final PrivacyService service;

    @Operation(summary = "Download everything CIMS holds about me")
    @GetMapping("/api/account/privacy/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON).body(service.export());
    }

    @GetMapping("/api/account/privacy/requests")
    @PreAuthorize("isAuthenticated()")
    public List<PrivacyService.RequestView> mine() {
        return service.mine();
    }

    @PostMapping("/api/account/privacy/requests")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public PrivacyService.RequestView submit(@Valid @RequestBody PrivacyService.RequestForm f) {
        return service.submit(f);
    }

    @GetMapping("/api/privacy/requests")
    @PreAuthorize(HANDLERS)
    public List<PrivacyService.RequestView> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @PostMapping("/api/privacy/requests/{id}/decision")
    @PreAuthorize(HANDLERS)
    public PrivacyService.RequestView decide(@PathVariable Long id, @Valid @RequestBody PrivacyService.Decision d) {
        return service.decide(id, d);
    }

    @GetMapping("/api/privacy/retention")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public PrivacyService.RetentionPreview preview() {
        return service.preview();
    }

    @PostMapping("/api/privacy/retention/run")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public Map<String, Integer> run() {
        return service.runRetention(CurrentUser.name());
    }
}
