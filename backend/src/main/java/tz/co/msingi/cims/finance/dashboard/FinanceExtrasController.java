package tz.co.msingi.cims.finance.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos;
import tz.co.msingi.cims.finance.adjustments.AdjustmentService;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos;
import tz.co.msingi.cims.finance.sponsors.SponsorService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** The Bursar's dashboard and targets, sponsors and sponsor statements, waivers and refunds (FR-FIN-08 to 12, BP-17). */
@Tag(name = "Finance: dashboard, sponsors, waivers and refunds")
@RestController
@RequiredArgsConstructor
public class FinanceExtrasController {

    private static final String FINANCE = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String BURSARY = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT')";
    private static final String SPONSOR_READERS = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR','SPONSOR_OFFICER')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final FinanceDashboardService dashboard;
    private final SponsorService sponsors;
    private final AdjustmentService adjustments;

    public record TargetsRequest(@NotNull List<FinanceDashboardService.TargetDto> targets) {
    }

    public record ThresholdRequest(@NotNull java.math.BigDecimal approvalThreshold) {
    }

    // ------------------------------------------------------------------ dashboard and targets

    @GetMapping("/api/finance/dashboard")
    @PreAuthorize(FINANCE)
    public FinanceDashboardService.Dashboard dashboard(@RequestParam(required = false) Long academicYearId) {
        return dashboard.dashboard(academicYearId);
    }

    @GetMapping("/api/finance/targets")
    @PreAuthorize(FINANCE)
    public List<FinanceDashboardService.TargetDto> targets(@RequestParam Long academicYearId) {
        return dashboard.targets(academicYearId);
    }

    @PutMapping("/api/finance/targets")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public List<FinanceDashboardService.TargetDto> saveTargets(@RequestParam Long academicYearId, @Valid @RequestBody TargetsRequest req) {
        return dashboard.saveTargets(academicYearId, req.targets());
    }

    @GetMapping(value = "/api/finance/postings.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','AUDITOR')")
    public ResponseEntity<byte[]> postings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"postings-" + from + "-to-" + to + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv")).body(dashboard.postingsCsv(from, to).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ sponsors

    @GetMapping("/api/finance/sponsors")
    @PreAuthorize(SPONSOR_READERS)
    public List<SponsorDtos.SponsorRow> sponsors() {
        return sponsors.list();
    }

    @PostMapping("/api/finance/sponsors")
    @PreAuthorize(BURSARY)
    public SponsorDtos.SponsorRow createSponsor(@Valid @RequestBody SponsorDtos.SponsorRequest req) {
        return sponsors.create(req);
    }

    @PutMapping("/api/finance/sponsors/{id}")
    @PreAuthorize(BURSARY)
    public SponsorDtos.SponsorRow updateSponsor(@PathVariable Long id, @Valid @RequestBody SponsorDtos.SponsorRequest req) {
        return sponsors.update(id, req);
    }

    @GetMapping("/api/finance/sponsors/{id}")
    @PreAuthorize(SPONSOR_READERS)
    public SponsorDtos.SponsorDetail sponsor(@PathVariable Long id, @RequestParam(required = false) Long academicYearId) {
        return sponsors.detail(id, academicYearId);
    }

    @PostMapping("/api/finance/sponsors/{id}/sponsorships")
    @PreAuthorize(BURSARY)
    public SponsorDtos.SponsorshipView addSponsorship(@PathVariable Long id, @Valid @RequestBody SponsorDtos.SponsorshipRequest req) {
        return sponsors.addSponsorship(id, req);
    }

    @PostMapping("/api/finance/sponsorships/{id}/end")
    @PreAuthorize(BURSARY)
    public SponsorDtos.SponsorshipView endSponsorship(@PathVariable Long id) {
        return sponsors.endSponsorship(id);
    }

    @PostMapping(value = "/api/finance/sponsors/{id}/import", consumes = {"text/csv", "text/plain", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @PreAuthorize(BURSARY)
    public SponsorDtos.ImportResult importList(@PathVariable Long id, @RequestParam Long academicYearId, @RequestBody byte[] csv) {
        return sponsors.importList(id, academicYearId, new String(csv, StandardCharsets.UTF_8));
    }

    @PostMapping("/api/finance/sponsors/{id}/invoices")
    @PreAuthorize(BURSARY)
    public SponsorDtos.InvoiceRunResult invoiceSponsor(@PathVariable Long id, @Valid @RequestBody SponsorDtos.InvoiceRunRequest req) {
        return sponsors.invoice(id, req.academicYearId());
    }

    @PostMapping("/api/finance/sponsor-invoices/{id}/remittances")
    @PreAuthorize(BURSARY)
    public SponsorDtos.SponsorInvoice remittance(@PathVariable Long id, @Valid @RequestBody SponsorDtos.RemittanceRequest req) {
        return sponsors.remittance(id, req);
    }

    @PostMapping("/api/finance/sponsor-invoices/{id}/control-number")
    @PreAuthorize(SPONSOR_READERS)
    public SponsorDtos.SponsorInvoice sponsorControlNumber(@PathVariable Long id) {
        return sponsors.controlNumber(id);
    }

    @GetMapping("/api/finance/sponsors/{id}/statement.xlsx")
    @PreAuthorize(SPONSOR_READERS)
    public ResponseEntity<byte[]> sponsorStatement(@PathVariable Long id, @RequestParam(required = false) Long academicYearId) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sponsor-statement.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX)).body(sponsors.statementExcel(id, academicYearId));
    }

    // ------------------------------------------------------------------ waivers and refunds

    @GetMapping("/api/finance/adjustments")
    @PreAuthorize(FINANCE)
    public List<AdjustmentDtos.AdjustmentView> adjustments(@RequestParam(defaultValue = "false") boolean open) {
        return adjustments.list(open);
    }

    @GetMapping("/api/finance/adjustments/options")
    @PreAuthorize(BURSARY)
    public AdjustmentDtos.StudentOptions adjustmentOptions(@RequestParam(required = false) Long studentId, @RequestParam(required = false) String registrationNo) {
        return adjustments.options(studentId, registrationNo);
    }

    @PostMapping("/api/finance/adjustments")
    @PreAuthorize(BURSARY)
    public AdjustmentDtos.AdjustmentView requestAdjustment(@Valid @RequestBody AdjustmentDtos.AdjustmentRequest req) {
        return adjustments.request(req);
    }

    @PostMapping("/api/finance/adjustments/{id}/approve")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR','MANAGEMENT_VIEWER')")
    public AdjustmentDtos.AdjustmentView approve(@PathVariable Long id, @Valid @RequestBody(required = false) AdjustmentDtos.DecisionRequest req) {
        return adjustments.approve(id, req == null ? null : req.note());
    }

    @PostMapping("/api/finance/adjustments/{id}/reject")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR','MANAGEMENT_VIEWER')")
    public AdjustmentDtos.AdjustmentView reject(@PathVariable Long id, @Valid @RequestBody AdjustmentDtos.DecisionRequest req) {
        return adjustments.reject(id, req.note());
    }

    @PostMapping("/api/finance/adjustments/{id}/paid")
    @PreAuthorize(BURSARY)
    public AdjustmentDtos.AdjustmentView paid(@PathVariable Long id, @Valid @RequestBody AdjustmentDtos.PaidRequest req) {
        return adjustments.markPaid(id, req.reference());
    }

    @GetMapping("/api/finance/settings")
    @PreAuthorize(FINANCE)
    public AdjustmentDtos.SettingsView settings() {
        return adjustments.settings();
    }

    @PutMapping("/api/finance/settings")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public AdjustmentDtos.SettingsView updateSettings(@Valid @RequestBody ThresholdRequest req) {
        return adjustments.updateSettings(req.approvalThreshold());
    }
}
