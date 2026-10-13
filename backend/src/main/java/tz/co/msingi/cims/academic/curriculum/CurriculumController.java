package tz.co.msingi.cims.academic.curriculum;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CalendarView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumSummary;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.EventRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.EventView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.ModuleRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.ModuleView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.SemesterRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.SemesterView;

import java.util.List;

/** Academic calendar (FR-ACA-04), module catalogue and curricula (FR-ACA-02/03). */
@Tag(name = "Academic calendar and curricula")
@RestController
@RequestMapping("/api/academic")
@RequiredArgsConstructor
public class CurriculumController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR',"
            + "'REGISTRAR','ADMISSIONS_OFFICER','EXAMINATIONS_OFFICER','LECTURER','ASSESSOR','MANAGEMENT_VIEWER','AUDITOR','BURSAR','ACCOUNTANT','IPT_COORDINATOR')";
    private static final String CALENDAR = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String PREPARE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR')";
    private static final String APPROVE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final AcademicCalendarService calendar;
    private final CurriculumService curricula;

    // ------------------------------------------------------------------ calendar

    @GetMapping("/calendar")
    @PreAuthorize(READ + " or hasRole('STUDENT')")
    public CalendarView calendar(@RequestParam Long academicYearId) {
        return calendar.calendar(academicYearId);
    }

    @GetMapping("/semesters")
    @PreAuthorize(READ)
    public List<SemesterView> semesters() {
        return calendar.allSemesters();
    }

    @PostMapping("/semesters")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CALENDAR)
    public SemesterView createSemester(@Valid @RequestBody SemesterRequest req) {
        return calendar.createSemester(req);
    }

    @PutMapping("/semesters/{id}")
    @PreAuthorize(CALENDAR)
    public SemesterView updateSemester(@PathVariable Long id, @Valid @RequestBody SemesterRequest req) {
        return calendar.updateSemester(id, req);
    }

    @PostMapping("/calendar-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CALENDAR)
    public EventView createEvent(@Valid @RequestBody EventRequest req) {
        return calendar.createEvent(req);
    }

    @PutMapping("/calendar-events/{id}")
    @PreAuthorize(CALENDAR)
    public EventView updateEvent(@PathVariable Long id, @Valid @RequestBody EventRequest req) {
        return calendar.updateEvent(id, req);
    }

    @DeleteMapping("/calendar-events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(CALENDAR)
    public void deleteEvent(@PathVariable Long id) {
        calendar.deleteEvent(id);
    }

    // ------------------------------------------------------------------ modules

    @GetMapping("/modules")
    @PreAuthorize(READ)
    public List<ModuleView> modules() {
        return curricula.modules();
    }

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PREPARE)
    public ModuleView createModule(@Valid @RequestBody ModuleRequest req) {
        return curricula.createModule(req);
    }

    @PutMapping("/modules/{id}")
    @PreAuthorize(PREPARE)
    public ModuleView updateModule(@PathVariable Long id, @Valid @RequestBody ModuleRequest req) {
        return curricula.updateModule(id, req);
    }

    // ------------------------------------------------------------------ curricula

    @GetMapping("/curricula")
    @PreAuthorize(READ)
    public List<CurriculumSummary> curricula(@RequestParam(required = false) Long programmeId) {
        return curricula.curricula(programmeId);
    }

    @GetMapping("/curricula/{id}")
    @PreAuthorize(READ)
    public CurriculumView curriculum(@PathVariable Long id) {
        return curricula.get(id);
    }

    @PostMapping("/curricula")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PREPARE)
    public CurriculumView create(@Valid @RequestBody CurriculumRequest req) {
        return curricula.create(req);
    }

    @PutMapping("/curricula/{id}")
    @PreAuthorize(PREPARE)
    public CurriculumView update(@PathVariable Long id, @Valid @RequestBody CurriculumRequest req) {
        return curricula.update(id, req);
    }

    @Operation(summary = "Approve a draft curriculum (not by the person who prepared it)")
    @PostMapping("/curricula/{id}/approve")
    @PreAuthorize(APPROVE)
    public CurriculumView approve(@PathVariable Long id) {
        return curricula.approve(id);
    }

    @PostMapping("/curricula/{id}/revise")
    @PreAuthorize(PREPARE)
    public CurriculumView revise(@PathVariable Long id) {
        return curricula.revise(id);
    }

    @PostMapping("/curricula/{id}/retire")
    @PreAuthorize(APPROVE)
    public CurriculumView retire(@PathVariable Long id) {
        return curricula.retire(id);
    }
}
