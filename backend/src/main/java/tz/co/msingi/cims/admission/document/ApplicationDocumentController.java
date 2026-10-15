package tz.co.msingi.cims.admission.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Application documents")
@RestController
@RequiredArgsConstructor
public class ApplicationDocumentController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','REGISTRAR')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";
    private final ApplicationDocumentService service;

    @GetMapping("/api/admission/windows/{windowId}/document-types")
    @PreAuthorize(READ)
    public List<ApplicationDocumentService.TypeView> types(@PathVariable Long windowId) {
        return service.types(windowId);
    }

    @PostMapping("/api/admission/windows/{windowId}/document-types")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDocumentService.TypeView add(@PathVariable Long windowId, @Valid @RequestBody ApplicationDocumentService.TypeRequest r) {
        return service.addType(windowId, r);
    }

    @PutMapping("/api/admission/document-types/{id}")
    @PreAuthorize(WRITE)
    public ApplicationDocumentService.TypeView update(@PathVariable Long id, @Valid @RequestBody ApplicationDocumentService.TypeRequest r) {
        return service.updateType(id, r);
    }

    @DeleteMapping("/api/admission/document-types/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.deleteType(id);
    }

    @GetMapping("/api/admission/applications/{id}/documents")
    @PreAuthorize(READ)
    public List<ApplicationDocumentService.DocumentView> forApplication(@PathVariable Long id) {
        return service.forApplication(id);
    }

    @GetMapping("/api/admission/documents/{id}/file")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        return send(service.file(id));
    }

    @PostMapping("/api/admission/documents/{id}/review")
    @PreAuthorize(WRITE)
    public ApplicationDocumentService.DocumentView review(@PathVariable Long id, @Valid @RequestBody ApplicationDocumentService.Review r) {
        return service.review(id, r);
    }

    // ---------------------------------------------------------------- applicant

    @Operation(summary = "The documents this application needs, with what has been uploaded")
    @GetMapping("/api/applicant/applications/{id}/documents")
    @PreAuthorize("isAuthenticated()")
    public List<ApplicationDocumentService.DocumentView> mine(@PathVariable Long id) {
        return service.mine(id);
    }

    @PutMapping(value = "/api/applicant/applications/{id}/documents/{typeId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApplicationDocumentService.DocumentView upload(@PathVariable Long id, @PathVariable Long typeId, @RequestPart("file") MultipartFile file) throws IOException {
        return service.upload(id, typeId, file.getOriginalFilename(), file.getBytes());
    }

    @DeleteMapping("/api/applicant/applications/{id}/documents/{typeId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id, @PathVariable Long typeId) {
        service.remove(id, typeId);
    }

    @GetMapping("/api/applicant/applications/{id}/documents/{typeId}/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> myFile(@PathVariable Long id, @PathVariable Long typeId) {
        return send(service.myFile(id, typeId));
    }

    private static ResponseEntity<byte[]> send(ApplicationDocumentService.FileContent f) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.data());
    }
}
