package tz.co.msingi.cims.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Students' health insurance with NHIF: the student's own cover, the Registrar's and the bursary's work on it. */
@Tag(name = "Health insurance (NHIF)")
@RestController
@RequiredArgsConstructor
public class HealthCoverController {

    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC','AUDITOR')";
    static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    static final String BURSARY = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT')";

    private final HealthCoverService service;

    public record CardRequest(@NotBlank @Size(max = 40) String cardNo) {
    }

    // ------------------------------------------------------------------ student portal

    @Operation(summary = "The student's health cover for the current academic year")
    @GetMapping("/api/student/health-cover")
    @PreAuthorize("hasRole('STUDENT')")
    public HealthCoverService.StudentCover mine() {
        return service.mine();
    }

    @Operation(summary = "The student gives their own NHIF card number; it is checked with NHIF")
    @PutMapping("/api/student/health-cover/card")
    @PreAuthorize("hasRole('STUDENT')")
    public HealthCoverService.StudentCover myCard(@Valid @RequestBody CardRequest req) {
        return service.recordMyCard(req.cardNo());
    }

    @Operation(summary = "The student has no cover: register me with NHIF through the institution (bills the NHIF contribution)")
    @PostMapping("/api/student/health-cover/institution")
    @PreAuthorize("hasRole('STUDENT')")
    public HealthCoverService.StudentCover myInstitution() {
        return service.chooseInstitutionForMe();
    }

    // ------------------------------------------------------------------ a student's record

    @GetMapping("/api/registry/students/{id}/health-cover")
    @PreAuthorize(READ)
    public HealthCoverService.StudentCover forStudent(@PathVariable Long id) {
        return service.forStudent(id);
    }

    @PutMapping("/api/registry/students/{id}/health-cover/card")
    @PreAuthorize(REGISTRY)
    public HealthCoverService.StudentCover card(@PathVariable Long id, @Valid @RequestBody CardRequest req) {
        return service.recordCard(id, req.cardNo());
    }

    @PostMapping("/api/registry/students/{id}/health-cover/institution")
    @PreAuthorize(REGISTRY)
    public HealthCoverService.StudentCover institution(@PathVariable Long id) {
        return service.chooseInstitution(id);
    }

    // ------------------------------------------------------------------ the office

    @Operation(summary = "Health cover of current students for an academic year (the current one by default)")
    @GetMapping("/api/health-covers/overview")
    @PreAuthorize(READ)
    public HealthCoverService.Overview overview(@RequestParam(required = false) Long academicYearId) {
        return service.overview(academicYearId);
    }

    @Operation(summary = "Current students with their cover; status ACTIVE, EXPIRED, INVALID, AWAITING_PAYMENT, READY, SUBMITTED, REJECTED or NONE")
    @GetMapping("/api/health-covers")
    @PreAuthorize(READ)
    public List<HealthCoverService.Cover> list(@RequestParam(required = false) Long academicYearId, @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Long programmeId, @RequestParam(required = false) String q) {
        return service.list(academicYearId, status, programmeId, q);
    }

    @Operation(summary = "Check every own NHIF card recorded for the year with NHIF again")
    @PostMapping("/api/health-covers/recheck")
    @PreAuthorize(REGISTRY)
    public HealthCoverService.Result recheck(@RequestParam(required = false) Long academicYearId) {
        return service.recheck(academicYearId);
    }

    @Operation(summary = "Bill the NHIF contribution to every current student with no cover recorded for the year")
    @PostMapping("/api/health-covers/bill")
    @PreAuthorize(BURSARY)
    public HealthCoverService.Result bill(@RequestParam(required = false) Long academicYearId) {
        return service.billUncovered(academicYearId);
    }

    @Operation(summary = "Send students whose contribution is paid to NHIF for registration under the student package")
    @PostMapping("/api/health-covers/registrations")
    @PreAuthorize(REGISTRY)
    public HealthCoverService.Batch send(@RequestParam(required = false) Long academicYearId) {
        return service.sendToNhif(academicYearId);
    }

    @Operation(summary = "Ask NHIF how a registration batch went")
    @PostMapping("/api/health-covers/registrations/{id}/check")
    @PreAuthorize(REGISTRY)
    public HealthCoverService.Batch check(@PathVariable Long id) {
        return service.check(id);
    }

    @GetMapping("/api/health-covers/registrations/{id}/items")
    @PreAuthorize(READ)
    public List<HealthCoverService.Cover> items(@PathVariable Long id) {
        return service.batchItems(id);
    }

    @Operation(summary = "Students to register with NHIF, in a file for NHIF's portal (personal data: the download is audited)")
    @GetMapping(value = "/api/health-covers/registration-file", produces = "text/csv")
    @PreAuthorize(REGISTRY)
    public ResponseEntity<byte[]> file(@RequestParam(required = false) Long academicYearId) {
        byte[] body = ("﻿" + service.registrationFile(academicYearId)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nhif-student-registration.csv\"").body(body);
    }
}
