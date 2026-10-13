package tz.co.msingi.cims.exams.appeal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.exams.appeal.AppealDtos.AppealRequest;
import tz.co.msingi.cims.exams.appeal.AppealDtos.AppealView;
import tz.co.msingi.cims.exams.appeal.AppealDtos.AssignRequest;
import tz.co.msingi.cims.exams.appeal.AppealDtos.ExaminerOption;
import tz.co.msingi.cims.exams.appeal.AppealDtos.MarkRequest;
import tz.co.msingi.cims.exams.appeal.AppealDtos.NoteRequest;
import tz.co.msingi.cims.exams.appeal.AppealDtos.StudentAppeals;

import java.util.List;

/** Remarks and appeals of published results (FR-EXM-12, BP-11). */
@Tag(name = "Examinations")
@RestController
@RequiredArgsConstructor
public class AppealController {

    static final String SEES = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER','HEAD_OF_DEPARTMENT','LECTURER','ASSESSOR','PROGRAMME_COORDINATOR')";
    static final String OFFICE = "hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER')";

    private final AppealService service;

    @GetMapping("/api/student/appeals")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentAppeals mine() {
        return service.mine();
    }

    @Operation(summary = "Ask for a module's examination to be re-marked, or appeal its result, within the appeal period")
    @PostMapping("/api/student/appeals")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentAppeals request(@Valid @RequestBody AppealRequest req) {
        return service.request(req);
    }

    @PostMapping("/api/student/appeals/{id}/withdraw")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentAppeals withdraw(@PathVariable Long id) {
        return service.withdraw(id);
    }

    @GetMapping("/api/exams/appeals")
    @PreAuthorize(SEES)
    public List<AppealView> list(@RequestParam(defaultValue = "true") boolean open) {
        return service.list(open);
    }

    @GetMapping("/api/exams/appeals/{id}")
    @PreAuthorize(SEES)
    public AppealView get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/api/exams/appeals/{id}/examiners")
    @PreAuthorize(OFFICE)
    public List<ExaminerOption> examiners(@PathVariable Long id) {
        return service.examiners(id);
    }

    @PostMapping("/api/exams/appeals/{id}/assign")
    @PreAuthorize(OFFICE)
    public AppealView assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return service.assign(id, req.examinerUserId());
    }

    @PostMapping("/api/exams/appeals/{id}/marks")
    @PreAuthorize(SEES)
    public AppealView mark(@PathVariable Long id, @Valid @RequestBody MarkRequest req) {
        return service.mark(id, req);
    }

    @PostMapping("/api/exams/appeals/{id}/forward")
    @PreAuthorize(OFFICE)
    public AppealView forward(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return service.forward(id, req == null ? null : req.note());
    }

    @PostMapping("/api/exams/appeals/{id}/reject")
    @PreAuthorize(OFFICE)
    public AppealView reject(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        return service.reject(id, req.note());
    }
}
