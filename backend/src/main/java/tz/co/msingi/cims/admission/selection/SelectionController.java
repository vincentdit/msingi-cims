package tz.co.msingi.cims.admission.selection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.selection.SelectionDtos.ApproveRequest;
import tz.co.msingi.cims.admission.selection.SelectionDtos.ExcludeRequest;
import tz.co.msingi.cims.admission.selection.SelectionDtos.RoundDetail;
import tz.co.msingi.cims.admission.selection.SelectionDtos.RoundSummary;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Selection rounds for an admission window (BP-03). */
@Tag(name = "Admissions office — selection")
@RestController
@RequestMapping("/api/admission")
@RequiredArgsConstructor
public class SelectionController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','REGISTRAR','MANAGEMENT_VIEWER')";
    private static final String PROPOSE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";
    private static final String COMMITTEE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final SelectionService service;

    @GetMapping("/windows/{windowId}/selection-rounds")
    @PreAuthorize(READ)
    public List<RoundSummary> rounds(@PathVariable Long windowId) {
        return service.list(windowId);
    }

    @Operation(summary = "Run a selection proposal (or re-run the round awaiting approval)")
    @PostMapping("/windows/{windowId}/selection-rounds")
    @PreAuthorize(PROPOSE)
    public RoundDetail propose(@PathVariable Long windowId) {
        return service.propose(windowId);
    }

    @GetMapping("/selection-rounds/{roundId}")
    @PreAuthorize(READ)
    public RoundDetail round(@PathVariable Long roundId) {
        return service.detail(roundId);
    }

    @Operation(summary = "Committee review: exclude an applicant with a reason; places are re-allocated")
    @PostMapping("/selection-rounds/{roundId}/exclusions")
    @PreAuthorize(PROPOSE + " or " + COMMITTEE)
    public RoundDetail exclude(@PathVariable Long roundId, @Valid @RequestBody ExcludeRequest req) {
        return service.exclude(roundId, req.applicationId(), req.reason());
    }

    @DeleteMapping("/selection-rounds/{roundId}/exclusions/{applicationId}")
    @PreAuthorize(PROPOSE + " or " + COMMITTEE)
    public RoundDetail reinstate(@PathVariable Long roundId, @PathVariable Long applicationId) {
        return service.reinstate(roundId, applicationId);
    }

    @Operation(summary = "Admissions Committee approval (must be a different person from the proposer)")
    @PostMapping("/selection-rounds/{roundId}/approve")
    @PreAuthorize(COMMITTEE)
    public RoundDetail approve(@PathVariable Long roundId, @Valid @RequestBody ApproveRequest req) {
        return service.approve(roundId, req.committeeMinuteRef(), req.notes());
    }

    @PostMapping("/selection-rounds/{roundId}/cancel")
    @PreAuthorize(PROPOSE + " or " + COMMITTEE)
    public RoundSummary cancel(@PathVariable Long roundId) {
        return service.cancel(roundId);
    }

    @Operation(summary = "Download the round as CSV for the committee pack")
    @GetMapping(value = "/selection-rounds/{roundId}/export", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> export(@PathVariable Long roundId) {
        byte[] body = ("﻿" + service.exportCsv(roundId)).getBytes(StandardCharsets.UTF_8); // BOM so Excel reads UTF-8
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"selection-round-" + roundId + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
