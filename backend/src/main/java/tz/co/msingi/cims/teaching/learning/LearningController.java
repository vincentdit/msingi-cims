package tz.co.msingi.cims.teaching.learning;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Learning materials and assignments (FR-TCH-06). Who may do what is checked in the service, per module offering. */
@Tag(name = "Learning materials")
@RestController
@RequiredArgsConstructor
public class LearningController {

    private final LearningService service;

    public record MarkRequest(BigDecimal score, String feedback) {
    }

    @GetMapping("/api/teaching/offerings/{id}/materials")
    @PreAuthorize(StaffAccess.ANY)
    public List<LearningService.MaterialView> list(@PathVariable Long id) {
        return service.forOffering(id);
    }

    @Operation(summary = "Post a note, link or assignment for a module offering (multipart: the fields and an optional file of up to 20 MB)")
    @PostMapping(value = "/api/teaching/offerings/{id}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(StaffAccess.ANY)
    @ResponseStatus(HttpStatus.CREATED)
    public LearningService.MaterialView post(@PathVariable Long id, @RequestParam(defaultValue = "NOTE") String kind, @RequestParam String title,
                                             @RequestParam(required = false) String body, @RequestParam(required = false) String linkUrl,
                                             @RequestParam(required = false) OffsetDateTime dueAt, @RequestParam(required = false) BigDecimal maxScore,
                                             @RequestParam(required = false) Boolean acceptLate, @RequestParam(required = false) Boolean published,
                                             @RequestParam(required = false) Boolean tellClass, @RequestPart(required = false) MultipartFile file) {
        return service.post(id, new LearningService.MaterialRequest(kind, title, body, linkUrl, dueAt, maxScore, acceptLate, published, tellClass), file);
    }

    @PutMapping("/api/teaching/materials/{id}")
    @PreAuthorize(StaffAccess.ANY)
    public LearningService.MaterialView update(@PathVariable Long id, @RequestBody LearningService.MaterialRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/api/teaching/materials/{id}")
    @PreAuthorize(StaffAccess.ANY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/api/teaching/materials/{id}/file")
    @PreAuthorize(StaffAccess.ANY)
    public ResponseEntity<byte[]> staffFile(@PathVariable Long id) {
        return send(service.file(id));
    }

    @GetMapping("/api/teaching/materials/{id}/submissions")
    @PreAuthorize(StaffAccess.ANY)
    public LearningService.AssignmentSheet submissions(@PathVariable Long id) {
        return service.submissions(id);
    }

    @GetMapping("/api/teaching/submissions/{id}/file")
    @PreAuthorize(StaffAccess.ANY)
    public ResponseEntity<byte[]> submissionFile(@PathVariable Long id) {
        return send(service.submissionFile(id));
    }

    @PutMapping("/api/teaching/submissions/{id}/mark")
    @PreAuthorize(StaffAccess.ANY)
    public LearningService.SubmissionView mark(@PathVariable Long id, @RequestBody MarkRequest r) {
        return service.mark(id, r.score(), r.feedback());
    }

    // ------------------------------------------------------------------ students

    @GetMapping("/api/student/modules")
    @PreAuthorize("hasRole('STUDENT')")
    public List<LearningService.MyModule> myModules() {
        return service.myModules();
    }

    @GetMapping("/api/student/modules/{offeringId}/materials")
    @PreAuthorize("hasRole('STUDENT')")
    public List<LearningService.MaterialView> myMaterials(@PathVariable Long offeringId) {
        return service.myMaterials(offeringId);
    }

    @GetMapping("/api/student/materials/{id}/file")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<byte[]> studentFile(@PathVariable Long id) {
        return send(service.file(id));
    }

    @Operation(summary = "Hand in an assignment (multipart: file of up to 10 MB, optional note); again before marking replaces it")
    @PostMapping(value = "/api/student/materials/{id}/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public LearningService.MaterialView handIn(@PathVariable Long id, @RequestPart MultipartFile file, @RequestParam(required = false) String note) {
        return service.handIn(id, file, note);
    }

    @GetMapping("/api/student/submissions/{id}/file")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<byte[]> mySubmission(@PathVariable Long id) {
        return send(service.submissionFile(id));
    }

    private static ResponseEntity<byte[]> send(LearningService.FileContent f) {
        return UploadedFile.download(f.fileName(), f.contentType(), f.data());
    }
}
