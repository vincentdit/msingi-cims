package tz.co.msingi.cims.admission.verification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.verification.VerificationDtos.ApplicantRow;
import tz.co.msingi.cims.admission.verification.VerificationDtos.ImportRequest;
import tz.co.msingi.cims.admission.verification.VerificationDtos.OutcomeResult;
import tz.co.msingi.cims.admission.verification.VerificationDtos.Overview;
import tz.co.msingi.cims.admission.verification.VerificationDtos.ResolveQueryRequest;
import tz.co.msingi.cims.admission.verification.VerificationDtos.SubmissionView;
import tz.co.msingi.cims.admission.verification.VerificationDtos.SubmitRequest;

import java.nio.charset.StandardCharsets;

/** NACTVET admission verification and offer tracking for an admission window (BP-04). */
@Tag(name = "Admissions office — NACTVET verification")
@RestController
@RequestMapping("/api/admission")
@RequiredArgsConstructor
public class VerificationController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','REGISTRAR','MANAGEMENT_VIEWER')";
    private static final String OFFICE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";

    private final VerificationService service;

    @Operation(summary = "Verification overview: applicants ready to submit, submissions, outcomes and offers")
    @GetMapping("/windows/{windowId}/verification")
    @PreAuthorize(READ)
    public Overview overview(@PathVariable Long windowId) {
        return service.overview(windowId);
    }

    @Operation(summary = "Submit selected applicants that pass pre-validation to NACTVET (API or file)")
    @PostMapping("/windows/{windowId}/nactvet-submissions")
    @PreAuthorize(OFFICE)
    public SubmissionView submit(@PathVariable Long windowId, @Valid @RequestBody SubmitRequest req) {
        return service.submit(windowId, req.channel(), req.paymentReference());
    }

    @Operation(summary = "Fetch outcomes of an API submission from NACTVET")
    @PostMapping("/nactvet-submissions/{id}/fetch-outcomes")
    @PreAuthorize(OFFICE)
    public OutcomeResult fetch(@PathVariable Long id) {
        return service.fetchOutcomes(id);
    }

    @Operation(summary = "Import NACTVET's outcome file (CSV: reference,index_number,outcome,reason,verification_no,multiple_admission)")
    @PostMapping("/nactvet-submissions/{id}/outcomes")
    @PreAuthorize(OFFICE)
    public OutcomeResult importOutcomes(@PathVariable Long id, @Valid @RequestBody ImportRequest req) {
        return service.importOutcomes(id, req.csv());
    }

    @Operation(summary = "Download the submission (NACTVET template) or a pre-filled outcome template")
    @GetMapping(value = "/nactvet-submissions/{id}/file", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> file(@PathVariable Long id, @RequestParam(defaultValue = "submission") String kind) {
        boolean outcomes = "outcomes".equals(kind);
        String csv = outcomes ? service.outcomeTemplate(id) : service.submissionFile(id);
        String name = (outcomes ? "nactvet-outcomes-" : "nactvet-submission-") + id + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Record how a NACTVET query was resolved; the applicant is included in the next submission")
    @PostMapping("/applications/{applicationId}/resolve-query")
    @PreAuthorize(OFFICE)
    public ApplicantRow resolve(@PathVariable Long applicationId, @Valid @RequestBody ResolveQueryRequest req) {
        return service.resolveQuery(applicationId, req.remarks());
    }
}
