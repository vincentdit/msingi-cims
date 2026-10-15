package tz.co.msingi.cims.admission.letter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.ApplicantProfileService;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import tz.co.msingi.cims.platform.letters.LetterTemplateService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Admission letters with joining instructions (FR-ADM-13): issued when an applicant accepts the offer,
 * printed with a QR verification code, reissued as a new edition after a correction, revoked if needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionLetterService {

    private final tz.co.msingi.cims.notification.email.EmailService email;
    private final LetterTemplateService letterTemplates;

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AdmissionLetterRepository letters;
    private final ApplicationRepository applications;
    private final AdmissionWindowRepository windows;
    private final ApplicantProfileService profiles;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final NumberSequenceService sequences;
    private final Messaging messaging;
    private final AuditService audit;
    private final ObjectMapper json;

    @Value("${cims.documents.verification-url:https://{host}/verify/{code}}")
    private String verificationUrlTemplate;

    // ------------------------------------------------------------------ configuration

    @Transactional
    public JoiningInstructions setJoiningInstructions(Long windowId, JoiningInstructions instructions) {
        AdmissionWindow w = windows.findById(windowId).orElseThrow(() -> new NotFoundException("Admission window", windowId));
        if (instructions.registrationDeadline() != null && instructions.registrationDeadline().isBefore(instructions.reportingDate())) {
            throw new BusinessRuleException("INVALID_DATES", "The registration deadline cannot be before the reporting date");
        }
        w.setJoiningInstructions(instructions);
        audit.record("JOINING_INSTRUCTIONS_SET", "AdmissionWindow", windowId, Map.of("reportingDate", instructions.reportingDate().toString()));
        return instructions;
    }

    // ------------------------------------------------------------------ issuing

    /** Issues the letter for an accepted application; returns the existing valid letter if there is one. */
    @Transactional
    public AdmissionLetter issue(Long applicationId) {
        Application app = requireApplication(applicationId);
        return letters.findByApplicationIdAndStatus(applicationId, AdmissionLetter.Status.VALID)
                .orElseGet(() -> create(app, null));
    }

    /** Used after an acceptance has committed: the letter is issued in its own transaction (tenant already set by the caller). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdmissionLetter issueInNewTransaction(Long applicationId) {
        return issue(applicationId);
    }

    /** Issues letters to every accepted applicant of the window who does not have a valid one yet. */
    @Transactional
    public int issuePending(Long windowId) {
        AdmissionWindow w = windows.findById(windowId).orElseThrow(() -> new NotFoundException("Admission window", windowId));
        requireConfigured(w);
        List<Application> accepted = applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(windowId, Set.of(Application.Status.ACCEPTED));
        Set<Long> withLetter = letters.findByApplicationIdInAndStatus(accepted.stream().map(Application::getId).toList(),
                AdmissionLetter.Status.VALID).stream().map(l -> l.getApplication().getId()).collect(Collectors.toSet());
        int issued = 0;
        for (Application a : accepted) {
            if (!withLetter.contains(a.getId())) {
                create(a, null);
                issued++;
            }
        }
        if (issued == 0) {
            throw new BusinessRuleException("NOTHING_TO_ISSUE", "Every accepted applicant already has a valid admission letter");
        }
        return issued;
    }

    /** A corrected letter: the current edition is superseded and a new edition with a new code is issued. */
    @Transactional
    public AdmissionLetter reissue(Long applicationId, String reason) {
        Application app = requireApplication(applicationId);
        letters.findByApplicationIdAndStatus(applicationId, AdmissionLetter.Status.VALID).ifPresent(current -> {
            current.setStatus(AdmissionLetter.Status.SUPERSEDED);
            letters.saveAndFlush(current);
        });
        return create(app, reason.trim());
    }

    /** Revokes the valid letter: the verification page then reports it as revoked. */
    @Transactional
    public AdmissionLetter revoke(Long applicationId, String reason) {
        AdmissionLetter l = letters.findByApplicationIdAndStatus(applicationId, AdmissionLetter.Status.VALID)
                .orElseThrow(() -> new BusinessRuleException("NO_VALID_LETTER", "This applicant has no valid admission letter"));
        l.setStatus(AdmissionLetter.Status.REVOKED);
        l.setRevokedAt(OffsetDateTime.now());
        l.setRevokedByName(CurrentUser.name());
        l.setRevokeReason(reason.trim());
        audit.record("ADMISSION_LETTER_REVOKED", "AdmissionLetter", l.getId(), Map.of("letterNo", l.getLetterNo(), "reason", reason.trim()));
        return l;
    }

    private AdmissionLetter create(Application app, String reissueReason) {
        if (app.getStatus() != Application.Status.ACCEPTED) {
            throw new BusinessRuleException("NOT_ACCEPTED", "An admission letter is issued only after the applicant accepts the offer");
        }
        AdmissionWindow w = app.getWindow();
        requireConfigured(w);
        Tenant tenant = currentTenant();
        int edition = letters.maxEdition(app.getId()) + 1;
        String letterNo = edition == 1
                ? NumberSequenceService.format(tenant.getCode() + "/ADM/" + w.getAcademicYear().getStartDate().getYear() + "/",
                        sequences.next("ADMISSION-LETTER-" + w.getAcademicYear().getStartDate().getYear()), 6)
                : letters.findByApplicationIdOrderByEditionDesc(app.getId()).get(0).getLetterNo();
        String code = LetterCodes.newCode();
        Applicant ap = app.getApplicant();
        var p = app.getSelectedWindowProgramme().getProgramme();
        Map<String, Object> branding = tenant.getBranding() == null ? Map.of() : tenant.getBranding();
        LetterContent content = new LetterContent(tenant.getName(), tenant.getShortName(), tenant.getNactvetRegNo(),
                tenant.getContactPhone(), tenant.getContactEmail(), String.valueOf(branding.getOrDefault("primaryColor", "")),
                letterNo, edition, LocalDate.now(ZONE), ap.fullName(), address(ap), app.getReferenceNo(), app.getNactvetVerificationNo(),
                p.getName(), p.getAwardName(), p.getNactvetCode(), p.getDurationSemesters(), w.getAcademicYear().getCode(),
                w.getIntake().getName(), app.getOfferRespondedAt(), w.getJoiningInstructions(), code, verificationUrl(tenant, code))
                .withWording(Map.of(
                        LetterTemplateService.ADMISSION_INTRO, letterTemplates.body(LetterTemplateService.ADMISSION_INTRO),
                        LetterTemplateService.ADMISSION_TERMS, letterTemplates.body(LetterTemplateService.ADMISSION_TERMS),
                        LetterTemplateService.ADMISSION_PAYMENT, letterTemplates.body(LetterTemplateService.ADMISSION_PAYMENT)));

        AdmissionLetter l = new AdmissionLetter();
        l.setApplication(app);
        l.setLetterNo(letterNo);
        l.setEdition((short) edition);
        l.setVerificationCode(code);
        l.setContent(content);
        l.setContentSha256(sha256(content));
        l.setIssuedAt(OffsetDateTime.now());
        l.setIssuedBy(CurrentUser.id().orElse(null));
        l.setIssuedByName(CurrentUser.id().isPresent() ? CurrentUser.name() : "System");
        l.setReissueReason(reissueReason);
        letters.saveAndFlush(l);
        audit.record(edition == 1 ? "ADMISSION_LETTER_ISSUED" : "ADMISSION_LETTER_REISSUED", "AdmissionLetter", l.getId(),
                reissueReason == null ? Map.of("letterNo", letterNo, "edition", edition)
                        : Map.of("letterNo", letterNo, "edition", edition, "reason", reissueReason));
        messaging.event(edition == 1 ? "LETTER_ISSUED" : "LETTER_REISSUED", ap.getPhone(), Map.of("programme", p.getName(), "verificationCode", code));
        // the letter itself by email to a verified address (FR-ADM-13)
        email.verifiedEmailForPhone(ap.getPhone()).ifPresent(to -> email.queue(to, ap.fullName(),
                tz.co.msingi.cims.notification.email.EmailMessage.Kind.LETTER, edition == 1 ? "LETTER_ISSUED" : "LETTER_REISSUED",
                (edition == 1 ? "Your admission letter — " : "Your corrected admission letter — ") + tenant.getName(),
                "Dear " + ap.fullName() + ",\n\nYour admission letter for " + p.getName() + " (" + w.getAcademicYear().getCode() + ", "
                        + w.getIntake().getName() + " intake) is attached, with the joining instructions.\n\nLetter number: " + letterNo
                        + "\nVerification code: " + code + "\nAnyone can check the letter at " + verificationUrl(tenant, code)
                        + "\n\n" + tenant.getName() + "\n(Sent by CIMS; please do not reply to this email.)",
                new tz.co.msingi.cims.notification.email.EmailService.Attachment(letterNo.replaceAll("[^A-Za-z0-9]+", "-") + ".pdf",
                        "application/pdf", AdmissionLetterRenderer.pdf(content))));
        return l;
    }

    // ------------------------------------------------------------------ documents

    /** The applicant's own valid letter as PDF. */
    @Transactional(readOnly = true)
    public Download applicantPdf(Long applicationId) {
        Applicant me = profiles.currentApplicant();
        Application app = applications.findById(applicationId).filter(a -> a.getApplicant().getId().equals(me.getId()))
                .orElseThrow(() -> new NotFoundException("Application", applicationId));
        AdmissionLetter l = letters.findByApplicationIdAndStatus(app.getId(), AdmissionLetter.Status.VALID)
                .orElseThrow(() -> new NotFoundException("Your admission letter is not ready yet"));
        return download(l);
    }

    /** Staff copy of the valid letter (downloads of personal documents are audited). */
    @Transactional
    public Download staffPdf(Long applicationId) {
        AdmissionLetter l = letters.findByApplicationIdAndStatus(applicationId, AdmissionLetter.Status.VALID)
                .orElseThrow(() -> new NotFoundException("No valid admission letter for application " + applicationId));
        audit.record("ADMISSION_LETTER_DOWNLOADED", "AdmissionLetter", l.getId(), Map.of("letterNo", l.getLetterNo()));
        return download(l);
    }

    private Download download(AdmissionLetter l) {
        String file = "admission-letter-" + l.getLetterNo().replaceAll("[^A-Za-z0-9]+", "-") + (l.getEdition() > 1 ? "-e" + l.getEdition() : "") + ".pdf";
        return new Download(file, AdmissionLetterRenderer.pdf(l.getContent()));
    }

    public record Download(String filename, byte[] content) {
    }

    // ------------------------------------------------------------------ public verification (BP-16)

    /** What a third party sees for a verification code: enough to compare with the paper, nothing more. */
    @Transactional(readOnly = true)
    public Optional<LetterVerification> verify(String code) {
        return LetterCodes.normalise(code).flatMap(letters::findByVerificationCode).map(l -> {
            LetterContent c = l.getContent();
            return new LetterVerification("ADMISSION_LETTER", l.getStatus().name(), c.institutionName(), c.institutionNactvetNo(),
                    l.getLetterNo(), l.getEdition(), l.getIssuedAt(), c.applicantName(), c.programmeName(), c.awardName(),
                    c.academicYear(), c.intake(), c.joining().reportingDate(), c.nactvetVerificationNo(), l.getRevokedAt(),
                    letters.findByApplicationIdAndStatus(l.getApplication().getId(), AdmissionLetter.Status.VALID)
                            .filter(v -> l.getStatus() == AdmissionLetter.Status.SUPERSEDED).map(AdmissionLetter::getEdition).orElse(null));
        });
    }

    public record LetterVerification(String documentType, String status, String institution, String institutionNactvetNo,
                                     String letterNo, int edition, OffsetDateTime issuedAt, String holderName, String programme,
                                     String award, String academicYear, String intake, LocalDate reportingDate,
                                     String nactvetVerificationNo, OffsetDateTime revokedAt, Short currentEdition) {
    }

    // ------------------------------------------------------------------ helpers

    private void requireConfigured(AdmissionWindow w) {
        if (w.getJoiningInstructions() == null) {
            throw new BusinessRuleException("LETTER_NOT_CONFIGURED",
                    "Set the joining instructions for " + w.getName() + " before admission letters can be issued");
        }
    }

    private Application requireApplication(Long id) {
        return applications.findById(id).orElseThrow(() -> new NotFoundException("Application", id));
    }

    private Tenant currentTenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }

    private String verificationUrl(Tenant tenant, String code) {
        String host = domains.findFirstByTenantIdOrderByIdAsc(tenant.getId()).map(d -> d.getHost()).orElse("localhost");
        return verificationUrlTemplate.replace("{host}", host).replace("{code}", code);
    }

    private static String address(Applicant a) {
        return Stream.of(a.getPostalAddress(), a.getDistrict(), a.getRegion())
                .filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.joining("\n"));
    }

    private String sha256(LetterContent content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
