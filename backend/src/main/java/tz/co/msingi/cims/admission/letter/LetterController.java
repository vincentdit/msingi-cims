package tz.co.msingi.cims.admission.letter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.letter.AdmissionLetterService.Download;
import tz.co.msingi.cims.admission.letter.AdmissionLetterService.LetterVerification;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.common.web.RateLimiter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

/** Admission letters (FR-ADM-13) for staff and applicants, and the public verification endpoint (BP-16). */
@Tag(name = "Admission letters")
@RestController
@RequiredArgsConstructor
public class LetterController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','REGISTRAR','MANAGEMENT_VIEWER')";
    private static final String OFFICE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";
    private static final String CONFIGURE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String REVOKE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final AdmissionLetterService service;
    private final tz.co.msingi.cims.exams.TranscriptService transcripts;
    private final tz.co.msingi.cims.graduation.GraduationService graduations;
    private RateLimiter verifyLimiter;

    @Value("${cims.documents.verify-rate-limit:30}")
    void setVerifyLimit(int perMinute) {
        this.verifyLimiter = new RateLimiter(perMinute, 60_000, Clock.systemUTC());
    }

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record LetterSummary(Long id, String letterNo, int edition, String status, String verificationCode, OffsetDateTime issuedAt) {
        static LetterSummary of(AdmissionLetter l) {
            return new LetterSummary(l.getId(), l.getLetterNo(), l.getEdition(), l.getStatus().name(), l.getVerificationCode(), l.getIssuedAt());
        }
    }

    // ---- staff

    @Operation(summary = "Set the joining instructions printed with the window's admission letters")
    @PutMapping("/api/admission/windows/{windowId}/joining-instructions")
    @PreAuthorize(CONFIGURE)
    public JoiningInstructions setJoiningInstructions(@PathVariable Long windowId, @Valid @RequestBody JoiningInstructions req) {
        return service.setJoiningInstructions(windowId, req);
    }

    @Operation(summary = "Issue letters to accepted applicants who do not have one yet")
    @PostMapping("/api/admission/windows/{windowId}/admission-letters")
    @PreAuthorize(OFFICE)
    public Map<String, Integer> issuePending(@PathVariable Long windowId) {
        return Map.of("issued", service.issuePending(windowId));
    }

    @GetMapping(value = "/api/admission/applications/{applicationId}/admission-letter", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> staffPdf(@PathVariable Long applicationId) {
        return pdf(service.staffPdf(applicationId));
    }

    @Operation(summary = "Issue a corrected letter (new edition and code; the old one verifies as superseded)")
    @PostMapping("/api/admission/applications/{applicationId}/admission-letter/reissue")
    @PreAuthorize(OFFICE)
    public LetterSummary reissue(@PathVariable Long applicationId, @Valid @RequestBody ReasonRequest req) {
        return LetterSummary.of(service.reissue(applicationId, req.reason()));
    }

    @Operation(summary = "Revoke the valid letter; verification then reports it as revoked")
    @PostMapping("/api/admission/applications/{applicationId}/admission-letter/revoke")
    @PreAuthorize(REVOKE)
    public LetterSummary revoke(@PathVariable Long applicationId, @Valid @RequestBody ReasonRequest req) {
        return LetterSummary.of(service.revoke(applicationId, req.reason()));
    }

    // ---- applicant

    @GetMapping(value = "/api/applicant/applications/{applicationId}/admission-letter", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('APPLICANT')")
    public ResponseEntity<byte[]> applicantPdf(@PathVariable Long applicationId) {
        return pdf(service.applicantPdf(applicationId));
    }

    // ---- public (BP-16)

    @Operation(summary = "Verify an admission letter by its code (public, rate-limited)")
    @GetMapping("/api/public/verify/{code}")
    public ResponseEntity<Object> verify(@PathVariable String code, HttpServletRequest request) {
        if (!verifyLimiter.tryAcquire(clientIp(request))) {
            throw new TooManyRequestsException("Too many verification attempts. Please wait a minute and try again.");
        }
        // admission letters, statements of results / provisional transcripts (FR-EXM-13), award certificates (FR-GRD-03)
        Object v = service.verify(code).<Object>map(x -> x).or(() -> transcripts.verify(code)).or(() -> graduations.verify(code))
                .orElseThrow(() -> new NotFoundException("No document was found with this verification code"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);
    }

    private static ResponseEntity<byte[]> pdf(Download d) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .body(d.content());
    }

    /**
     * The client address as seen by the server. A client-supplied X-Forwarded-For header is not trusted here
     * (it would let anyone escape the limit); behind a reverse proxy set server.forward-headers-strategy so the
     * container reports the real client address.
     */
    private static String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
