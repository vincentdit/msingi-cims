package tz.co.msingi.cims.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.finance.fees.FeeDtos.FeeItemRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.FeeItemView;
import tz.co.msingi.cims.finance.fees.FeeDtos.GateRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.GateView;
import tz.co.msingi.cims.finance.fees.FeeDtos.LinesRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.StructureRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.StructureView;
import tz.co.msingi.cims.finance.fees.FeeService;
import tz.co.msingi.cims.finance.reports.FinanceReportService;
import tz.co.msingi.cims.finance.service.BillingService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Bursar's office: fees, billing, clearance, statements and reports (BP-06). */
@Tag(name = "Finance")
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String STUDENT_READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR','REGISTRAR')";
    private static final String EDIT = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT')";
    private static final String BURSAR = "hasAnyRole('INSTITUTION_ADMIN','BURSAR')";

    private final FeeService fees;
    private final AnnualBillingService billingRuns;
    private final AccountService accounts;
    private final BillingService billing;
    private final FinanceReportService reports;

    public record RunRequest(@NotNull Long academicYearId) {
    }

    // ---- fee items

    @GetMapping("/fee-items")
    @PreAuthorize(READ)
    public List<FeeItemView> items() {
        return fees.items();
    }

    @PostMapping("/fee-items")
    @PreAuthorize(EDIT)
    public FeeItemView createItem(@Valid @RequestBody FeeItemRequest req) {
        return fees.createItem(req);
    }

    @PutMapping("/fee-items/{id}")
    @PreAuthorize(EDIT)
    public FeeItemView updateItem(@PathVariable Long id, @Valid @RequestBody FeeItemRequest req) {
        return fees.updateItem(id, req);
    }

    // ---- fee structures

    @GetMapping("/fee-structures")
    @PreAuthorize(READ)
    public List<StructureView> structures(@RequestParam Long academicYearId) {
        return fees.structures(academicYearId);
    }

    @GetMapping("/fee-structures/{id}")
    @PreAuthorize(READ)
    public StructureView structure(@PathVariable Long id) {
        return fees.structure(id);
    }

    @Operation(summary = "Prepare a fee structure (draft); the next version if one is already approved")
    @PostMapping("/fee-structures")
    @PreAuthorize(EDIT)
    public StructureView create(@Valid @RequestBody StructureRequest req) {
        return fees.create(req);
    }

    @PutMapping("/fee-structures/{id}")
    @PreAuthorize(EDIT)
    public StructureView update(@PathVariable Long id, @Valid @RequestBody LinesRequest req) {
        return fees.updateDraft(id, req.notes(), req.lines());
    }

    @Operation(summary = "Start a new version from the approved structure")
    @PostMapping("/fee-structures/{id}/revise")
    @PreAuthorize(EDIT)
    public StructureView revise(@PathVariable Long id) {
        return fees.revise(id);
    }

    @Operation(summary = "Bursar approval (not by the preparer); supersedes the previous version for new invoices")
    @PostMapping("/fee-structures/{id}/approve")
    @PreAuthorize(BURSAR)
    public StructureView approve(@PathVariable Long id) {
        return fees.approve(id);
    }

    // ---- clearance gates

    @GetMapping("/clearance-gates")
    @PreAuthorize(STUDENT_READ)
    public List<GateView> gates() {
        return fees.gates();
    }

    @PutMapping("/clearance-gates/{gate}")
    @PreAuthorize(BURSAR)
    public List<GateView> updateGate(@PathVariable ClearanceGate.Gate gate, @Valid @RequestBody GateRequest req) {
        return fees.updateGate(gate, req);
    }

    // ---- billing

    @Operation(summary = "Billing run: annual invoices for new students and accepted applicants of the year who have none")
    @PostMapping("/billing-runs")
    @PreAuthorize(EDIT)
    public AnnualBillingService.RunResult run(@Valid @RequestBody RunRequest req) {
        return billingRuns.run(req.academicYearId());
    }

    @Operation(summary = "Request (or reuse) the GePG control number of an invoice")
    @PostMapping("/invoices/{id}/control-number")
    @PreAuthorize(EDIT + " or hasRole('REGISTRAR')")
    public java.util.Map<String, Object> controlNumber(@PathVariable Long id) {
        var cn = billing.ensureControlNumber(id);
        return java.util.Map.of("controlNo", cn.getControlNo() == null ? "" : cn.getControlNo(), "expiresAt", cn.getExpiresAt());
    }

    // ---- students

    @GetMapping("/students/{studentId}/statement")
    @PreAuthorize(STUDENT_READ)
    public Statement statement(@PathVariable Long studentId) {
        return accounts.forStudent(studentId);
    }

    @GetMapping("/students/{studentId}/clearance")
    @PreAuthorize(STUDENT_READ)
    public Clearance clearance(@PathVariable Long studentId, @RequestParam(defaultValue = "REGISTRATION") ClearanceGate.Gate gate) {
        return accounts.clearance(studentId, gate);
    }

    // ---- reports (FR-FIN-12)

    @GetMapping("/reports/collections")
    @PreAuthorize(READ)
    public FinanceReportService.Collections collections(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.collections(from, to);
    }

    @GetMapping(value = "/reports/collections.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> collectionsCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv("collections-" + from + "-to-" + to + ".csv", reports.collectionsCsv(from, to));
    }

    @GetMapping("/reports/debtors")
    @PreAuthorize(READ)
    public FinanceReportService.Debtors debtors(@RequestParam(required = false) Long academicYearId) {
        return reports.debtors(academicYearId);
    }

    @GetMapping(value = "/reports/debtors.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> debtorsCsv(@RequestParam(required = false) Long academicYearId) {
        return csv("debtors.csv", reports.debtorsCsv(academicYearId));
    }

    private static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(("﻿" + body).getBytes(StandardCharsets.UTF_8));
    }
}
