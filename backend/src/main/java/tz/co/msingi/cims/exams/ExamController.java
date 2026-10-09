package tz.co.msingi.cims.exams;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import tz.co.msingi.cims.exams.ExamDtos.ComputeRequest;
import tz.co.msingi.cims.exams.ExamDtos.DecisionRequest;
import tz.co.msingi.cims.exams.ExamDtos.MarksWindowRequest;
import tz.co.msingi.cims.exams.ExamDtos.NoteRequest;
import tz.co.msingi.cims.exams.ExamDtos.OfferingSummary;
import tz.co.msingi.cims.exams.ExamDtos.OfferingView;
import tz.co.msingi.cims.exams.ExamDtos.OptionalNote;
import tz.co.msingi.cims.exams.ExamDtos.PlanRequest;
import tz.co.msingi.cims.exams.ExamDtos.RegulationRequest;
import tz.co.msingi.cims.exams.ExamDtos.RegulationView;
import tz.co.msingi.cims.exams.ExamDtos.ScoresRequest;
import tz.co.msingi.cims.exams.ExamDtos.SettingsView;
import tz.co.msingi.cims.exams.ExamDtos.SetupRequest;
import tz.co.msingi.cims.exams.ExamDtos.SetupResult;
import tz.co.msingi.cims.exams.ExamDtos.SheetSummary;
import tz.co.msingi.cims.exams.ExamDtos.SheetView;
import tz.co.msingi.cims.exams.ExamDtos.StaffOption;
import tz.co.msingi.cims.exams.ExamDtos.StaffRequest;
import tz.co.msingi.cims.exams.ExamDtos.StudentResults;
import tz.co.msingi.cims.exams.ExamDtos.UploadResult;

import java.util.List;

/** Examinations and results (FR-ACA-05, FR-EXM-01/02/03/05/06/07/10, BP-08). */
@Tag(name = "Examinations and results")
@RestController
@RequiredArgsConstructor
public class ExamController {

    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR',"
            + "'EXAMINATIONS_OFFICER','LECTURER','ASSESSOR','MANAGEMENT_VIEWER')";
    private static final String RESULTS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER',"
            + "'MANAGEMENT_VIEWER')";
    private static final String REGULATORS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','EXAMINATIONS_OFFICER')";
    private static final String ALLOCATORS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER')";
    private static final String EXAMS_OFFICE = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER')";
    private static final String COMMITTEE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final RegulationService regulations;
    private final OfferingService offerings;
    private final ResultSheetService sheets;

    // ------------------------------------------------------------------ regulations

    @GetMapping("/api/exams/regulations")
    @PreAuthorize(STAFF)
    public List<RegulationView> regulations() {
        return regulations.list();
    }

    @GetMapping("/api/exams/regulations/{id}")
    @PreAuthorize(STAFF)
    public RegulationView regulation(@PathVariable Long id) {
        return regulations.get(id);
    }

    @PostMapping("/api/exams/regulations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(REGULATORS)
    public RegulationView createRegulation(@Valid @RequestBody RegulationRequest req) {
        return regulations.create(req);
    }

    @PutMapping("/api/exams/regulations/{id}")
    @PreAuthorize(REGULATORS)
    public RegulationView updateRegulation(@PathVariable Long id, @Valid @RequestBody RegulationRequest req) {
        return regulations.update(id, req);
    }

    @GetMapping("/api/exams/settings")
    @PreAuthorize(STAFF)
    public SettingsView settings() {
        return new SettingsView(regulations.settings().isUseDepartmentBoard());
    }

    @PutMapping("/api/exams/settings")
    @PreAuthorize(COMMITTEE)
    public SettingsView updateSettings(@RequestBody SettingsView req) {
        return regulations.updateSettings(req);
    }

    // ------------------------------------------------------------------ offerings, plans and marks

    @PostMapping("/api/exams/offerings/setup")
    @PreAuthorize(ALLOCATORS)
    public SetupResult setup(@Valid @RequestBody SetupRequest req) {
        return offerings.setup(req.semesterId());
    }

    @PutMapping("/api/exams/semesters/{id}/marks-window")
    @PreAuthorize(EXAMS_OFFICE)
    public void marksWindow(@PathVariable Long id, @RequestBody MarksWindowRequest req) {
        offerings.marksWindow(id, req.marksEntryOpens(), req.marksEntryCloses());
    }

    @GetMapping("/api/exams/staff")
    @PreAuthorize(ALLOCATORS)
    public List<StaffOption> staff() {
        return offerings.staffOptions();
    }

    @GetMapping("/api/exams/offerings")
    @PreAuthorize(STAFF)
    public List<OfferingSummary> offerings(@RequestParam Long semesterId, @RequestParam(defaultValue = "false") boolean mine) {
        return offerings.list(semesterId, mine);
    }

    @GetMapping("/api/exams/offerings/{id}")
    @PreAuthorize(STAFF)
    public OfferingView offering(@PathVariable Long id) {
        return offerings.get(id);
    }

    @PutMapping("/api/exams/offerings/{id}/staff")
    @PreAuthorize(ALLOCATORS)
    public OfferingView assign(@PathVariable Long id, @Valid @RequestBody StaffRequest req) {
        return offerings.assign(id, req.userIds());
    }

    @PutMapping("/api/exams/offerings/{id}/plan")
    @PreAuthorize(STAFF)
    public OfferingView plan(@PathVariable Long id, @Valid @RequestBody PlanRequest req) {
        return offerings.savePlan(id, req.components());
    }

    @PutMapping("/api/exams/offerings/{id}/scores")
    @PreAuthorize(STAFF)
    public OfferingView scores(@PathVariable Long id, @Valid @RequestBody ScoresRequest req) {
        return offerings.saveScores(id, req.entries());
    }

    @GetMapping("/api/exams/offerings/{id}/marks-template")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> template(@PathVariable Long id) {
        OfferingView o = offerings.get(id);
        String name = (o.code() + " " + o.semesterName() + " marks.xlsx").replaceAll("[^A-Za-z0-9 ._-]", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType(XLSX))
                .body(offerings.template(id));
    }

    @PostMapping(value = "/api/exams/offerings/{id}/marks-upload", consumes = {XLSX, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @PreAuthorize(STAFF)
    public UploadResult upload(@PathVariable Long id, @RequestBody byte[] file) {
        return offerings.upload(id, file);
    }

    @PostMapping("/api/exams/offerings/{id}/submit")
    @PreAuthorize(STAFF)
    public OfferingView submit(@PathVariable Long id) {
        return offerings.submit(id);
    }

    @PostMapping("/api/exams/offerings/{id}/hod-approve")
    @PreAuthorize(ALLOCATORS)
    public OfferingView hodApprove(@PathVariable Long id) {
        return offerings.hodApprove(id);
    }

    @PostMapping("/api/exams/offerings/{id}/board-approve")
    @PreAuthorize(ALLOCATORS)
    public OfferingView boardApprove(@PathVariable Long id) {
        return offerings.boardApprove(id);
    }

    @PostMapping("/api/exams/offerings/{id}/return")
    @PreAuthorize(ALLOCATORS)
    public OfferingView returnToLecturer(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return offerings.returnToLecturer(id, req.note());
    }

    // ------------------------------------------------------------------ class result sheets

    @GetMapping("/api/exams/sheets")
    @PreAuthorize(RESULTS)
    public List<SheetSummary> sheets(@RequestParam Long semesterId) {
        return sheets.list(semesterId);
    }

    @GetMapping("/api/exams/sheets/{id}")
    @PreAuthorize(RESULTS)
    public SheetView sheet(@PathVariable Long id) {
        return sheets.get(id);
    }

    @PostMapping("/api/exams/sheets/compute")
    @PreAuthorize(EXAMS_OFFICE)
    public SheetView compute(@Valid @RequestBody ComputeRequest req) {
        return sheets.compute(req);
    }

    @PutMapping("/api/exams/sheets/{id}/students/{studentId}/decision")
    @PreAuthorize(COMMITTEE)
    public SheetView decide(@PathVariable Long id, @PathVariable Long studentId, @Valid @RequestBody DecisionRequest req) {
        return sheets.decide(id, studentId, req);
    }

    @PostMapping("/api/exams/sheets/{id}/recommend")
    @PreAuthorize(COMMITTEE)
    public SheetView recommend(@PathVariable Long id, @Valid @RequestBody(required = false) OptionalNote req) {
        return sheets.recommend(id, req == null ? null : req.note());
    }

    @PostMapping("/api/exams/sheets/{id}/confirm")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public SheetView confirm(@PathVariable Long id) {
        return sheets.confirm(id);
    }

    @PostMapping("/api/exams/sheets/{id}/return")
    @PreAuthorize(COMMITTEE)
    public SheetView returnSheet(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return sheets.returnSheet(id, req.note());
    }

    @PostMapping("/api/exams/sheets/{id}/publish")
    @PreAuthorize(EXAMS_OFFICE)
    public SheetView publish(@PathVariable Long id) {
        return sheets.publish(id);
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/results")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentResults myResults() {
        return sheets.mine();
    }
}
