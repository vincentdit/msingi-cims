package tz.co.msingi.cims.registry.idcard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.BatchPreview;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.BatchRequest;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.IdCardSummary;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.IssueRequest;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.RevokeRequest;

import java.io.IOException;

/** Student photos and ID cards (FR-REG-06). */
@Tag(name = "Registry")
@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class IdCardController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final IdCardService service;

    @GetMapping("/students/{id}/id-card")
    @PreAuthorize(READ)
    public IdCardSummary summary(@PathVariable Long id) {
        return service.summary(id);
    }

    @GetMapping("/students/{id}/photo")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        StudentPhoto p = service.photo(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(p.getContentType())).body(p.getData());
    }

    @Operation(summary = "Upload or replace a student's passport-style photo (JPEG or PNG, up to 2 MB)")
    @PutMapping(value = "/students/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(REGISTRY)
    public IdCardSummary uploadPhoto(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
        return service.uploadPhoto(id, file.getBytes());
    }

    @Operation(summary = "Issue an ID card, or a replacement (reason required) when the student already has a valid one")
    @PostMapping("/students/{id}/id-card")
    @PreAuthorize(REGISTRY)
    public IdCardSummary issue(@PathVariable Long id, @Valid @RequestBody(required = false) IssueRequest req) {
        return service.issue(id, req == null ? null : req.reason());
    }

    @PostMapping("/students/{id}/id-cards/{cardId}/revoke")
    @PreAuthorize(REGISTRY)
    public IdCardSummary revoke(@PathVariable Long id, @PathVariable Long cardId, @Valid @RequestBody RevokeRequest req) {
        return service.revoke(id, cardId, req.reason());
    }

    @GetMapping(value = "/students/{id}/id-card.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(REGISTRY)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        return pdf(service.pdf(id));
    }

    @GetMapping("/id-cards/batch")
    @PreAuthorize(REGISTRY)
    public BatchPreview preview(@RequestParam Long programmeId, @RequestParam(required = false) Short yearOfStudy) {
        return service.batchPreview(programmeId, yearOfStudy);
    }

    @Operation(summary = "Issue cards to every ready student of a programme (and year) and download them as one PDF")
    @PostMapping(value = "/id-cards/batch", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(REGISTRY)
    public ResponseEntity<byte[]> batch(@Valid @RequestBody BatchRequest req) {
        return pdf(service.issueBatch(req.programmeId(), req.yearOfStudy()));
    }

    private static ResponseEntity<byte[]> pdf(IdCardService.Download d) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF).body(d.content());
    }
}
