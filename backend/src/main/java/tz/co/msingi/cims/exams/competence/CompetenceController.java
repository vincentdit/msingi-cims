package tz.co.msingi.cims.exams.competence;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AssessorsRequest;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AwardClass;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AwardList;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.ConfirmRequest;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.EntriesRequest;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.MyUnit;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.NoteRequest;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.SetupResult;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.TasksRequest;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.UnitSummary;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.UnitView;

import java.util.List;

/** NVA competence-based assessment (BP-09, FR-EXM-09). */
@Tag(name = "NVA competence assessment")
@RestController
@RequiredArgsConstructor
public class CompetenceController {

    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR',"
            + "'EXAMINATIONS_OFFICER','LECTURER','ASSESSOR','MANAGEMENT_VIEWER')";
    private static final String ALLOCATORS = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER','HEAD_OF_DEPARTMENT','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String AWARDS = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CompetenceService service;

    @GetMapping("/api/competence/units")
    @PreAuthorize(STAFF)
    public List<UnitSummary> units(@RequestParam Long semesterId, @RequestParam(defaultValue = "false") boolean mine) {
        return service.list(semesterId, mine);
    }

    @PostMapping("/api/competence/semesters/{id}/setup")
    @PreAuthorize(ALLOCATORS)
    public SetupResult setup(@PathVariable Long id) {
        return service.setup(id);
    }

    @GetMapping("/api/competence/units/{id}")
    @PreAuthorize(STAFF)
    public UnitView unit(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/api/competence/units/{id}/tasks")
    @PreAuthorize(ALLOCATORS)
    public UnitView tasks(@PathVariable Long id, @Valid @RequestBody TasksRequest req) {
        return service.saveTasks(id, req.tasks());
    }

    @PutMapping("/api/competence/units/{id}/assessors")
    @PreAuthorize(ALLOCATORS)
    public UnitView assessors(@PathVariable Long id, @Valid @RequestBody AssessorsRequest req) {
        return service.assign(id, req.userIds());
    }

    @PutMapping("/api/competence/units/{id}/assessments")
    @PreAuthorize(STAFF)
    public UnitView record(@PathVariable Long id, @Valid @RequestBody EntriesRequest req) {
        return service.record(id, req.entries());
    }

    @PostMapping("/api/competence/units/{id}/submit")
    @PreAuthorize(STAFF)
    public UnitView submit(@PathVariable Long id) {
        return service.submit(id);
    }

    @PostMapping("/api/competence/units/{id}/verify")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','HEAD_OF_DEPARTMENT')")
    public UnitView verify(@PathVariable Long id) {
        return service.verify(id);
    }

    @PostMapping("/api/competence/units/{id}/return")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','HEAD_OF_DEPARTMENT')")
    public UnitView returnUnit(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return service.returnUnit(id, req.note());
    }

    @PostMapping("/api/competence/units/{id}/reopen")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER')")
    public UnitView reopen(@PathVariable Long id) {
        return service.reopen(id);
    }

    // ------------------------------------------------------------------ award eligibility

    @GetMapping("/api/competence/awards")
    @PreAuthorize(AWARDS)
    public List<AwardClass> awardClasses(@RequestParam Long semesterId) {
        return service.awardClasses(semesterId);
    }

    @GetMapping("/api/competence/awards/list")
    @PreAuthorize(AWARDS)
    public AwardList awardList(@RequestParam Long semesterId, @RequestParam Long programmeId, @RequestParam short yearOfStudy) {
        return service.awardList(semesterId, programmeId, yearOfStudy);
    }

    @PostMapping("/api/competence/awards/confirm")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER')")
    public AwardList confirm(@Valid @RequestBody ConfirmRequest req) {
        return service.confirm(req.semesterId(), req.programmeId(), req.yearOfStudy());
    }

    @GetMapping("/api/competence/awards/list.xlsx")
    @PreAuthorize(AWARDS)
    public ResponseEntity<byte[]> awardExcel(@RequestParam Long semesterId, @RequestParam Long programmeId, @RequestParam short yearOfStudy) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nva-award-eligibility.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX)).body(service.awardExcel(semesterId, programmeId, yearOfStudy));
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/competence")
    @PreAuthorize("hasRole('STUDENT')")
    public List<MyUnit> mine() {
        return service.myUnits();
    }
}
