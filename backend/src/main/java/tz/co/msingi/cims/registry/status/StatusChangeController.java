package tz.co.msingi.cims.registry.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.DecisionRequest;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.NoteRequest;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusChangeRequest;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusChangeView;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusOptions;

import java.util.List;

/** Student status changes: postponement, resumption, suspension, discontinuation, withdrawal and death (FR-REG-04, BP-14). */
@Tag(name = "Registry")
@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class StatusChangeController {

    static final String FOLLOW = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final StatusChangeService service;

    @GetMapping("/students/{id}/status-changes")
    @PreAuthorize(FOLLOW)
    public List<StatusChangeView> forStudent(@PathVariable Long id) {
        return service.forStudent(id);
    }

    @GetMapping("/students/{id}/status-options")
    @PreAuthorize(REGISTRY)
    public StatusOptions options(@PathVariable Long id) {
        return service.options(id);
    }

    @Operation(summary = "Request a status change for a student (goes to the Head of Department and the Deputy Principal)")
    @PostMapping("/students/{id}/status-changes")
    @PreAuthorize(REGISTRY)
    public StatusChangeView request(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest req) {
        return service.request(id, req);
    }

    @GetMapping("/status-changes")
    @PreAuthorize(FOLLOW)
    public List<StatusChangeView> list(@RequestParam(defaultValue = "true") boolean open) {
        return service.list(open);
    }

    @PostMapping("/status-changes/{id}/recommend")
    @PreAuthorize("hasRole('HEAD_OF_DEPARTMENT')")
    public StatusChangeView recommend(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return service.recommend(id, req == null ? null : req.note());
    }

    @PostMapping("/status-changes/{id}/decision")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')")
    public StatusChangeView decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return service.decide(id, req.approve(), req.note());
    }

    @PostMapping("/status-changes/{id}/cancel")
    @PreAuthorize(REGISTRY)
    public StatusChangeView cancel(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return service.cancel(id, req == null ? null : req.note());
    }
}
