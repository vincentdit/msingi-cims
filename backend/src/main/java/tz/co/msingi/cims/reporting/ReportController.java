package tz.co.msingi.cims.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.reporting.ReportModel.CatalogueEntry;
import tz.co.msingi.cims.reporting.ReportModel.Filter;
import tz.co.msingi.cims.reporting.ReportModel.Report;

import java.time.LocalDate;
import java.util.List;

/** Standard reports (FR-RPT-02) and role-based dashboards (FR-RPT-01). Each report checks the caller's roles itself. */
@Tag(name = "Reports and dashboards")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR',"
            + "'HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER','BURSAR','ACCOUNTANT','HOSTEL_WARDEN','IPT_COORDINATOR','AUDITOR')";

    private final ReportService reports;
    private final DashboardService dashboards;
    private final OverviewService overview;

    @Operation(summary = "The reports this user may run")
    @GetMapping("/api/reports")
    @PreAuthorize(STAFF)
    public List<CatalogueEntry> catalogue() {
        return reports.catalogue();
    }

    @Operation(summary = "Run a report (first 500 rows for the screen)")
    @GetMapping("/api/reports/{code}")
    @PreAuthorize(STAFF)
    public Report run(@PathVariable String code, @RequestParam(required = false) Long academicYearId, @RequestParam(required = false) Long intakeId,
                      @RequestParam(required = false) Long windowId, @RequestParam(required = false) Long programmeId,
                      @RequestParam(required = false) String awardFamily, @RequestParam(required = false) Integer level,
                      @RequestParam(required = false) String sex, @RequestParam(required = false) String status,
                      @RequestParam(required = false) Integer yearOfStudy,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @RequestParam(required = false) String text, @RequestParam(required = false) Long semesterId,
                      @RequestParam(required = false) Long moduleId) {
        return reports.view(code, new Filter(academicYearId, intakeId, windowId, programmeId, awardFamily, level, sex, status, yearOfStudy, from, to, text,
                semesterId, moduleId));
    }

    @Operation(summary = "Download a report as Excel (format=xlsx) or PDF (format=pdf)")
    @GetMapping("/api/reports/{code}/export")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> export(@PathVariable String code, @RequestParam String format, @RequestParam(required = false) Long academicYearId,
                                         @RequestParam(required = false) Long intakeId, @RequestParam(required = false) Long windowId,
                                         @RequestParam(required = false) Long programmeId, @RequestParam(required = false) String awardFamily,
                                         @RequestParam(required = false) Integer level, @RequestParam(required = false) String sex,
                                         @RequestParam(required = false) String status, @RequestParam(required = false) Integer yearOfStudy,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String text, @RequestParam(required = false) Long semesterId,
                                         @RequestParam(required = false) Long moduleId) {
        ReportService.Export e = reports.export(code,
                new Filter(academicYearId, intakeId, windowId, programmeId, awardFamily, level, sex, status, yearOfStudy, from, to, text, semesterId, moduleId), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(e.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(e.contentType()))
                .body(e.content());
    }

    @Operation(summary = "Dashboard sections for this user's roles")
    @GetMapping("/api/dashboard")
    @PreAuthorize(STAFF)
    public DashboardService.Dashboard dashboard() {
        return dashboards.dashboard();
    }

    @Operation(summary = "Institution overview for the Principal and management: enrolment, intake, fees, results and demand by programme")
    @GetMapping("/api/dashboard/overview")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC')")
    public OverviewService.Overview overview(@org.springframework.web.bind.annotation.RequestParam(required = false) Long academicYearId) {
        return overview.overview(academicYearId);
    }
}
