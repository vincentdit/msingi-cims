package tz.co.msingi.cims.platform.branding;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.admission.letter.AdmissionLetterRenderer;
import tz.co.msingi.cims.common.document.OfficialMarks;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.branding.PrincipalService.Mark;
import tz.co.msingi.cims.platform.letters.LetterTemplateController;
import tz.co.msingi.cims.platform.letters.LetterTemplateService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Principal who signs the institution's letters and documents, managed by the institution administrator. */
@Tag(name = "Principal (signatory)")
@RestController
@RequestMapping("/api/admin/institution/principal")
@RequiredArgsConstructor
@PreAuthorize("hasRole('INSTITUTION_ADMIN')")
public class PrincipalController {

    private final PrincipalService service;
    private final LetterTemplateService letterTemplates;
    private final TenantRepository tenants;

    @GetMapping
    public ResponseEntity<PrincipalService.PrincipalView> view() {
        return service.view().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Every change to the Principal's details, newest first")
    @GetMapping("/history")
    public List<PrincipalService.HistoryRow> history() {
        return service.history();
    }

    @Operation(summary = "Record the Principal's name and title (a different person starts without a signature)")
    @PutMapping
    public PrincipalService.PrincipalView save(@Valid @RequestBody PrincipalService.Save s) {
        return service.save(s);
    }

    @Operation(summary = "The current scanned signature or stamp (never served publicly)")
    @GetMapping("/{mark}")
    public ResponseEntity<byte[]> image(@PathVariable String mark) {
        OfficialMarks.Image i = service.image(mark(mark));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(i.type())).cacheControl(CacheControl.noStore()).body(i.data());
    }

    @Operation(summary = "Upload the scanned signature or stamp (PNG or JPEG, up to 3 MB); the white paper is made see-through unless keepBackground")
    @PutMapping(value = "/{mark}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PrincipalService.PrincipalView upload(@PathVariable String mark, @RequestPart("file") MultipartFile file,
                                                 @RequestParam(value = "keepBackground", defaultValue = "false") boolean keepBackground) throws IOException {
        return service.upload(mark(mark), file.getBytes(), keepBackground);
    }

    @DeleteMapping("/{mark}")
    public PrincipalService.PrincipalView remove(@PathVariable String mark) {
        return service.remove(mark(mark));
    }

    @Operation(summary = "A sample admission letter showing the logo, signature and stamp as they will print")
    @GetMapping(value = "/sample", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> sample() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        Map<String, String> wording = new HashMap<>();
        for (String c : List.of(LetterTemplateService.ADMISSION_INTRO, LetterTemplateService.ADMISSION_TERMS, LetterTemplateService.ADMISSION_PAYMENT)) {
            wording.put(c, letterTemplates.body(c));
        }
        byte[] pdf = AdmissionLetterRenderer.pdf(LetterTemplateController.sampleAdmissionLetter(t, wording), service.marks());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition", "inline; filename=\"sample-signed-letter.pdf\"")
                .body(pdf);
    }

    private static Mark mark(String mark) {
        try {
            return Mark.valueOf(mark.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("No such mark");
        }
    }
}
