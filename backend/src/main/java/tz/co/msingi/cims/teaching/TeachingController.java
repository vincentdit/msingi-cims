package tz.co.msingi.cims.teaching;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.teaching.TeachingDtos.AttendanceView;
import tz.co.msingi.cims.teaching.TeachingDtos.DecisionNote;
import tz.co.msingi.cims.teaching.TeachingDtos.EligibilityOverview;
import tz.co.msingi.cims.teaching.TeachingDtos.ExemptionRequest;
import tz.co.msingi.cims.teaching.TeachingDtos.ExemptionView;
import tz.co.msingi.cims.teaching.TeachingDtos.GenerateResult;
import tz.co.msingi.cims.teaching.TeachingDtos.MyAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.OfferingAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.OfferingEligibilityView;
import tz.co.msingi.cims.teaching.TeachingDtos.RegisterLine;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionRequest;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionView;
import tz.co.msingi.cims.teaching.TeachingDtos.WorkloadView;

import java.util.List;

/** Teaching and attendance (FR-TCH-02/03/04, BP-07) and examination eligibility (FR-EXM-04, BRU-08). */
@Tag(name = "Teaching, attendance and examination eligibility")
@RestController
@RequiredArgsConstructor
public class TeachingController {

    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR',"
            + "'EXAMINATIONS_OFFICER','LECTURER','ASSESSOR','MANAGEMENT_VIEWER')";
    private static final String OVERSIGHT = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER',"
            + "'MANAGEMENT_VIEWER')";
    private static final String EXAMS_OFFICE = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER')";
    private static final String REQUESTERS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER')";
    private static final String COMMITTEE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final AttendanceService attendance;
    private final EligibilityService eligibility;
    private final CheckInService checkIns;
    private final MyTeachingService myTeaching;
    private final ClassListService classLists;

    // ------------------------------------------------------------------ the lecturer's own teaching

    @GetMapping("/api/teaching/mine")
    @PreAuthorize(STAFF)
    public TeachingDtos.MyTeaching mine(@RequestParam(required = false) Long semesterId) {
        return myTeaching.board(semesterId);
    }

    @GetMapping("/api/teaching/offerings/{id}/class-list")
    @PreAuthorize(STAFF)
    public org.springframework.http.ResponseEntity<byte[]> classList(@PathVariable Long id, @RequestParam(defaultValue = "pdf") String format) {
        ClassListService.Document d = "xlsx".equalsIgnoreCase(format) ? classLists.excel(id) : classLists.pdf(id);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.filename() + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(d.contentType())).body(d.bytes());
    }

    // ------------------------------------------------------------------ self check-in (FR-TCH-03)

    @PostMapping("/api/teaching/offerings/{id}/check-in")
    @PreAuthorize(STAFF)
    public SessionView openCheckIn(@PathVariable Long id, @Valid @RequestBody TeachingDtos.CheckInRequest req) {
        return checkIns.open(id, req);
    }

    @GetMapping("/api/teaching/sessions/{id}/check-in")
    @PreAuthorize(STAFF)
    public TeachingDtos.CheckInScreen checkInScreen(@PathVariable Long id, @RequestParam(required = false) String base) {
        return checkIns.screen(id, base);
    }

    @PostMapping("/api/teaching/sessions/{id}/check-in/close")
    @PreAuthorize(STAFF)
    public SessionView closeCheckIn(@PathVariable Long id) {
        return checkIns.close(id);
    }

    @PostMapping("/api/student/check-in")
    @PreAuthorize("hasRole('STUDENT')")
    public TeachingDtos.CheckInDone checkIn(@Valid @RequestBody TeachingDtos.StudentCheckIn req) {
        return checkIns.checkIn(req);
    }

    // ------------------------------------------------------------------ attendance

    @GetMapping("/api/teaching/attendance")
    @PreAuthorize(STAFF)
    public List<OfferingAttendance> summary(@RequestParam Long semesterId, @RequestParam(defaultValue = "false") boolean mine) {
        return attendance.summary(semesterId, mine);
    }

    @GetMapping("/api/teaching/offerings/{id}/attendance")
    @PreAuthorize(STAFF)
    public AttendanceView offeringAttendance(@PathVariable Long id) {
        return attendance.view(id);
    }

    @GetMapping("/api/teaching/offerings/{id}/register")
    @PreAuthorize(STAFF)
    public List<RegisterLine> register(@PathVariable Long id) {
        return attendance.register(id);
    }

    @PostMapping("/api/teaching/offerings/{id}/sessions")
    @PreAuthorize(STAFF)
    public SessionView record(@PathVariable Long id, @Valid @RequestBody SessionRequest req) {
        return attendance.record(id, req);
    }

    @GetMapping("/api/teaching/sessions/{id}")
    @PreAuthorize(STAFF)
    public SessionView session(@PathVariable Long id) {
        return attendance.session(id);
    }

    @PutMapping("/api/teaching/sessions/{id}")
    @PreAuthorize(STAFF)
    public SessionView update(@PathVariable Long id, @Valid @RequestBody SessionRequest req) {
        return attendance.update(id, req);
    }

    @DeleteMapping("/api/teaching/sessions/{id}")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        attendance.delete(id);
    }

    @GetMapping("/api/teaching/workload")
    @PreAuthorize(OVERSIGHT)
    public WorkloadView workload(@RequestParam Long semesterId) {
        return attendance.workload(semesterId);
    }

    // ------------------------------------------------------------------ examination eligibility

    @GetMapping("/api/exams/eligibility")
    @PreAuthorize(OVERSIGHT)
    public EligibilityOverview eligibility(@RequestParam Long semesterId) {
        return eligibility.overview(semesterId);
    }

    @PostMapping("/api/exams/semesters/{id}/eligibility")
    @PreAuthorize(EXAMS_OFFICE)
    public GenerateResult generate(@PathVariable Long id) {
        return eligibility.generate(id);
    }

    @GetMapping("/api/exams/offerings/{id}/eligibility")
    @PreAuthorize(STAFF)
    public OfferingEligibilityView offeringEligibility(@PathVariable Long id) {
        return eligibility.offering(id);
    }

    @PostMapping("/api/exams/offerings/{id}/exemptions")
    @PreAuthorize(REQUESTERS)
    public ExemptionView requestExemption(@PathVariable Long id, @Valid @RequestBody ExemptionRequest req) {
        return eligibility.request(id, req);
    }

    @GetMapping("/api/exams/exemptions")
    @PreAuthorize(REQUESTERS)
    public List<ExemptionView> exemptions(@RequestParam(defaultValue = "true") boolean pending) {
        return eligibility.exemptions(pending);
    }

    @PostMapping("/api/exams/exemptions/{id}/approve")
    @PreAuthorize(COMMITTEE)
    public ExemptionView approve(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionNote req) {
        return eligibility.approve(id, req == null ? null : req.note());
    }

    @PostMapping("/api/exams/exemptions/{id}/reject")
    @PreAuthorize(COMMITTEE)
    public ExemptionView reject(@PathVariable Long id, @Valid @RequestBody DecisionNote req) {
        return eligibility.reject(id, req.note());
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/attendance")
    @PreAuthorize("hasRole('STUDENT')")
    public MyAttendance myAttendance(@RequestParam(required = false) Long semesterId) {
        return attendance.mine(semesterId);
    }
}
