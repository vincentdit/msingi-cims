package tz.co.msingi.cims.registry.semester;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.ApproveAllRequest;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.ApproveAllResult;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.RegistrationView;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.RejectRequest;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.SemesterSummary;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.StudentRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.SubmitRequest;

import java.util.List;

/** Semester registration (FR-REG-03): the student's own page, and Registry's review and approval. */
@Tag(name = "Semester registration")
@RestController
@RequiredArgsConstructor
public class SemesterRegistrationController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final SemesterRegistrationService service;

    @GetMapping("/api/student/semester-registration")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentRegistration mine() {
        return service.mine();
    }

    @Operation(summary = "Register (or re-submit) for the semester with the chosen electives")
    @PostMapping("/api/student/semester-registration")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentRegistration submit(@Valid @RequestBody SubmitRequest req) {
        return service.submit(req.semesterId(), req.electiveModuleIds(), req.detailsConfirmed());
    }

    @PostMapping("/api/student/semester-registration/{id}/cancel")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentRegistration cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @GetMapping("/api/registry/semester-registrations")
    @PreAuthorize(READ)
    public List<RegistrationView> list(@RequestParam Long semesterId, @RequestParam(required = false) String status) {
        return service.list(semesterId, status);
    }

    @GetMapping("/api/registry/semester-registrations/summary")
    @PreAuthorize(READ)
    public SemesterSummary summary(@RequestParam Long semesterId) {
        return service.summary(semesterId);
    }

    @GetMapping("/api/registry/semester-registrations/{id}")
    @PreAuthorize(READ)
    public RegistrationView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/api/registry/semester-registrations/{id}/approve")
    @PreAuthorize(REGISTRY)
    public RegistrationView approve(@PathVariable Long id) {
        return service.approve(id);
    }

    @Operation(summary = "Approve every submitted registration whose fees are cleared")
    @PostMapping("/api/registry/semester-registrations/approve-all")
    @PreAuthorize(REGISTRY)
    public ApproveAllResult approveAll(@Valid @RequestBody ApproveAllRequest req) {
        return service.approveAll(req.semesterId());
    }

    @PostMapping("/api/registry/semester-registrations/{id}/reject")
    @PreAuthorize(REGISTRY)
    public RegistrationView reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        return service.reject(id, req.note());
    }
}
