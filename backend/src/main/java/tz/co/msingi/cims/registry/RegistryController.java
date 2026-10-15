package tz.co.msingi.cims.registry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.web.PageResponse;
import tz.co.msingi.cims.registry.RegistryDtos.ChecklistRequest;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverOverview;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverRequest;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverResult;
import tz.co.msingi.cims.registry.RegistryDtos.PatternRequest;
import tz.co.msingi.cims.registry.RegistryDtos.SettingsView;
import tz.co.msingi.cims.registry.RegistryDtos.StudentDetail;
import tz.co.msingi.cims.registry.RegistryDtos.StudentSummary;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Registry: registration hand-over, student records and first registration (BP-05, FR-REG-01/02). */
@Tag(name = "Registry")
@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class RegistryController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final RegistryService service;

    @GetMapping("/settings")
    @PreAuthorize(READ)
    public SettingsView settings() {
        return service.settings();
    }

    @Operation(summary = "Preview a registration number pattern without saving it")
    @PostMapping("/settings/preview")
    @PreAuthorize(REGISTRY)
    public SettingsView preview(@Valid @RequestBody PatternRequest req) {
        return service.preview(req.pattern());
    }

    @Operation(summary = "Set the registration number pattern (applies to new students only)")
    @PutMapping("/settings")
    @PreAuthorize(REGISTRY)
    public SettingsView update(@Valid @RequestBody PatternRequest req) {
        return service.updatePattern(req.pattern());
    }

    @Operation(summary = "Accepted applicants of a window and whether they have been handed over to Registry")
    @GetMapping("/windows/{windowId}/handover")
    @PreAuthorize(READ)
    public HandoverOverview handoverOverview(@PathVariable Long windowId) {
        return service.handoverOverview(windowId);
    }

    @Operation(summary = "Create student records with registration numbers (all pending, or the applications given)")
    @PostMapping("/windows/{windowId}/handover")
    @PreAuthorize(REGISTRY)
    public HandoverResult handover(@PathVariable Long windowId, @Valid @RequestBody(required = false) HandoverRequest req) {
        return service.handover(windowId, req == null ? null : req.applicationIds());
    }

    @GetMapping("/students")
    @PreAuthorize(READ)
    public PageResponse<StudentSummary> students(@RequestParam(required = false) Long programmeId,
                                                 @RequestParam(required = false) Student.Status status,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        return service.search(programmeId, status, q, page, size);
    }

    @GetMapping("/students/stats")
    @PreAuthorize(READ)
    public Map<String, Long> stats() {
        return service.stats();
    }

    @GetMapping("/students/{id}")
    @PreAuthorize(READ)
    public StudentDetail student(@PathVariable Long id) {
        return service.detail(id);
    }

    @Operation(summary = "Set the student's fee category (regular, evening, …)")
    @org.springframework.web.bind.annotation.PutMapping("/students/{id}/category")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','BURSAR')")
    public StudentDetail setCategory(@PathVariable Long id, @RequestBody RegistryDtos.CategoryRequest req) {
        return service.setCategory(id, req.studentCategoryId());
    }

    @Operation(summary = "Record the originals seen at reporting (first registration checklist)")
    @PutMapping("/students/{id}/first-registration")
    @PreAuthorize(REGISTRY)
    public StudentDetail saveChecklist(@PathVariable Long id, @Valid @RequestBody ChecklistRequest req) {
        return service.saveChecklist(id, req);
    }

    @Operation(summary = "Approve first registration; the student becomes ACTIVE")
    @PostMapping("/students/{id}/first-registration/approve")
    @PreAuthorize(REGISTRY)
    public StudentDetail approve(@PathVariable Long id) {
        return service.approveFirstRegistration(id);
    }

    @Operation(summary = "Class list for a programme (CSV)")
    @GetMapping(value = "/programmes/{programmeId}/class-list", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> classList(@PathVariable Long programmeId, @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("class-list-" + programmeId + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(("﻿" + service.classList(programmeId, activeOnly)).getBytes(StandardCharsets.UTF_8));
    }
}
