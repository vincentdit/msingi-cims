package tz.co.msingi.cims.graduation;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.graduation.GraduationDtos.ApproveRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.ClearanceSummary;
import tz.co.msingi.cims.graduation.GraduationDtos.ClearanceView;
import tz.co.msingi.cims.graduation.GraduationDtos.DecisionRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.Finalist;
import tz.co.msingi.cims.graduation.GraduationDtos.GenerateResult;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationSummary;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationView;
import tz.co.msingi.cims.graduation.GraduationDtos.IssueResult;
import tz.co.msingi.cims.graduation.GraduationDtos.ItemView;
import tz.co.msingi.cims.graduation.GraduationDtos.MyClearance;
import tz.co.msingi.cims.graduation.GraduationDtos.NactvetRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.NoteRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.OfficeRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.OfficeView;
import tz.co.msingi.cims.graduation.GraduationDtos.QueueLine;
import tz.co.msingi.cims.graduation.GraduationDtos.ReprintRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.StartRequest;

import java.util.List;

/** Clearance and graduation (FR-GRD-01..03, BP-15, UC-18). */
@Tag(name = "Clearance and graduation")
@RestController
@RequiredArgsConstructor
public class GraduationController {

    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR',"
            + "'HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR','LECTURER','ASSESSOR','EXAMINATIONS_OFFICER','BURSAR','ACCOUNTANT','HOSTEL_WARDEN',"
            + "'IPT_COORDINATOR','CLEARANCE_OFFICER','AUDITOR')";
    private static final String VIEWERS = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private static final String BOARD = "hasRole('INSTITUTION_ADMIN')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ClearanceService clearance;
    private final GraduationService graduation;

    // ------------------------------------------------------------------ clearance offices

    @GetMapping("/api/clearance/offices")
    @PreAuthorize(STAFF)
    public List<OfficeView> offices() {
        return clearance.offices();
    }

    @PostMapping("/api/clearance/offices")
    @PreAuthorize(REGISTRY)
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeView createOffice(@Valid @RequestBody OfficeRequest req) {
        return clearance.saveOffice(null, req);
    }

    @PutMapping("/api/clearance/offices/{id}")
    @PreAuthorize(REGISTRY)
    public OfficeView updateOffice(@PathVariable Long id, @Valid @RequestBody OfficeRequest req) {
        return clearance.saveOffice(id, req);
    }

    @GetMapping("/api/clearance/staff")
    @PreAuthorize(REGISTRY)
    public List<GraduationDtos.StaffOption> staff() {
        return clearance.staffOptions();
    }

    @GetMapping("/api/clearance/offices/{id}/queue")
    @PreAuthorize(STAFF)
    public List<QueueLine> queue(@PathVariable Long id) {
        return clearance.queue(id);
    }

    // ------------------------------------------------------------------ clearances

    @GetMapping("/api/clearance/finalists")
    @PreAuthorize(VIEWERS)
    public List<Finalist> finalists() {
        return clearance.finalists();
    }

    @GetMapping("/api/clearance/clearances")
    @PreAuthorize(VIEWERS)
    public List<ClearanceSummary> clearances() {
        return clearance.list();
    }

    @PostMapping("/api/clearance/clearances")
    @PreAuthorize(REGISTRY)
    public ClearanceView start(@Valid @RequestBody StartRequest req) {
        return clearance.start(req.studentId());
    }

    @GetMapping("/api/clearance/clearances/{id}")
    @PreAuthorize(STAFF)
    public ClearanceView clearance(@PathVariable Long id) {
        return clearance.get(id);
    }

    @PutMapping("/api/clearance/items/{id}")
    @PreAuthorize(STAFF)
    public ItemView decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return clearance.decide(id, req.status(), req.note());
    }

    // ------------------------------------------------------------------ graduations

    @GetMapping("/api/graduations")
    @PreAuthorize(VIEWERS)
    public List<GraduationSummary> graduations() {
        return graduation.list();
    }

    @PostMapping("/api/graduations")
    @PreAuthorize(REGISTRY)
    @ResponseStatus(HttpStatus.CREATED)
    public GraduationView create(@Valid @RequestBody GraduationRequest req) {
        return graduation.create(req);
    }

    @GetMapping("/api/graduations/{id}")
    @PreAuthorize(VIEWERS)
    public GraduationView get(@PathVariable Long id) {
        return graduation.get(id);
    }

    @PutMapping("/api/graduations/{id}")
    @PreAuthorize(REGISTRY)
    public GraduationView update(@PathVariable Long id, @Valid @RequestBody GraduationRequest req) {
        return graduation.update(id, req);
    }

    @PostMapping("/api/graduations/{id}/generate")
    @PreAuthorize(REGISTRY)
    public GenerateResult generate(@PathVariable Long id) {
        return graduation.generate(id);
    }

    @PostMapping("/api/graduations/{id}/submit")
    @PreAuthorize(REGISTRY)
    public GraduationView submit(@PathVariable Long id) {
        return graduation.submit(id);
    }

    @PostMapping("/api/graduations/{id}/approve")
    @PreAuthorize(BOARD)
    public GraduationView approve(@PathVariable Long id, @Valid @RequestBody ApproveRequest req) {
        return graduation.approve(id, req.boardMinuteRef());
    }

    @PostMapping("/api/graduations/{id}/return")
    @PreAuthorize(BOARD)
    public GraduationView returnList(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return graduation.returnToRegistry(id, req.note());
    }

    @GetMapping(value = "/api/graduations/{id}/nactvet-file", produces = XLSX)
    @PreAuthorize(REGISTRY)
    public ResponseEntity<byte[]> nactvetFile(@PathVariable Long id) {
        return file(graduation.nactvetFile(id), MediaType.parseMediaType(XLSX));
    }

    @PutMapping("/api/graduations/{id}/nactvet")
    @PreAuthorize(REGISTRY)
    public GraduationView nactvet(@PathVariable Long id, @Valid @RequestBody NactvetRequest req) {
        return graduation.nactvet(id, req.status(), req.reference(), req.note());
    }

    @PostMapping("/api/graduations/{id}/certificates")
    @PreAuthorize(REGISTRY)
    public IssueResult issueCertificates(@PathVariable Long id) {
        return graduation.issueCertificates(id);
    }

    /** All issued certificates (or one, with graduandId) as a PDF; reprints need a reason. */
    @PostMapping(value = "/api/graduations/{id}/certificates/print", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(REGISTRY)
    public ResponseEntity<byte[]> print(@PathVariable Long id, @RequestParam(required = false) Long graduandId,
                                        @RequestBody(required = false) ReprintRequest req) {
        return file(graduation.print(id, graduandId, req == null ? null : req.reason()), MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/clearance")
    @PreAuthorize("hasRole('STUDENT')")
    public MyClearance mine() {
        return clearance.mine();
    }

    @PostMapping("/api/student/clearance")
    @PreAuthorize("hasRole('STUDENT')")
    public MyClearance startMine() {
        return clearance.startMine();
    }

    private static ResponseEntity<byte[]> file(GraduationService.Download d, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore()).contentType(type).body(d.content());
    }
}
