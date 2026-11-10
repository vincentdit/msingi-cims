package tz.co.msingi.cims.quality;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.util.List;

/** Quality assurance: students' evaluation of modules and teaching (FR-QA-01..03). */
@Tag(name = "Quality assurance")
@RestController
@RequiredArgsConstructor
public class QualityController {

    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','QUALITY_ASSURANCE_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER','AUDITOR','HEAD_OF_DEPARTMENT')";
    static final String MANAGE = "hasAnyRole('INSTITUTION_ADMIN','QUALITY_ASSURANCE_OFFICER')";

    private final QualityService quality;

    @GetMapping("/api/qa/questions")
    @PreAuthorize(READ)
    public List<QualityService.Question> questions() {
        return quality.questions();
    }

    @PostMapping("/api/qa/questions")
    @PreAuthorize(MANAGE)
    public List<QualityService.Question> addQuestion(@RequestBody QualityService.QuestionRequest r) {
        return quality.saveQuestion(null, r);
    }

    @PutMapping("/api/qa/questions/{id}")
    @PreAuthorize(MANAGE)
    public List<QualityService.Question> saveQuestion(@PathVariable Long id, @RequestBody QualityService.QuestionRequest r) {
        return quality.saveQuestion(id, r);
    }

    @GetMapping("/api/qa/rounds")
    @PreAuthorize(READ)
    public List<QualityService.RoundView> rounds() {
        return quality.rounds();
    }

    @Operation(summary = "Open the evaluation of a semester's modules (tellStudents: SMS the registered students when it is open)")
    @PostMapping("/api/qa/rounds")
    @PreAuthorize(MANAGE)
    public QualityService.RoundDetail open(@RequestBody QualityService.RoundRequest r) {
        return quality.openRound(r);
    }

    @PutMapping("/api/qa/rounds/{id}")
    @PreAuthorize(MANAGE)
    public QualityService.RoundDetail change(@PathVariable Long id, @RequestBody QualityService.RoundRequest r) {
        return quality.changeRound(id, r);
    }

    @GetMapping("/api/qa/rounds/{id}")
    @PreAuthorize(READ)
    public QualityService.RoundDetail round(@PathVariable Long id) {
        return quality.round(id);
    }

    @Operation(summary = "A module's results: averages and spread per question; written comments once the evaluation has closed")
    @GetMapping("/api/qa/rounds/{id}/offerings/{offeringId}")
    @PreAuthorize(StaffAccess.ANY)
    public QualityService.ModuleReport module(@PathVariable Long id, @PathVariable Long offeringId) {
        return quality.moduleReport(id, offeringId);
    }

    @GetMapping("/api/qa/rounds/{id}/workbook")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> workbook(@PathVariable Long id) {
        return UploadedFile.download("module-evaluation-" + id + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                quality.workbook(id));
    }

    @Operation(summary = "The signed-in lecturer's modules in each evaluation")
    @GetMapping("/api/qa/my-modules")
    @PreAuthorize(StaffAccess.ANY)
    public List<QualityService.LecturerRound> lecturer() {
        return quality.lecturerResults();
    }

    @GetMapping("/api/student/evaluations")
    @PreAuthorize("hasRole('STUDENT')")
    public List<QualityService.MyEvaluation> mine() {
        return quality.mine();
    }

    @PostMapping("/api/student/evaluations/{roundId}/offerings/{offeringId}")
    @PreAuthorize("hasRole('STUDENT')")
    public List<QualityService.MyEvaluation> answer(@PathVariable Long roundId, @PathVariable Long offeringId,
                                                     @RequestBody QualityService.AnswerRequest r) {
        return quality.answer(roundId, offeringId, r);
    }
}
