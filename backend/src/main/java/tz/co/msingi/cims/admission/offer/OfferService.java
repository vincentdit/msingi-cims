package tz.co.msingi.cims.admission.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.Application.Status;
import tz.co.msingi.cims.admission.service.ApplicantProfileService;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway;
import tz.co.msingi.cims.notification.SmsSender;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admission offers (BP-04 steps 4–5, FR-ADM-14): issued when NACTVET verifies an applicant; accepted or
 * declined by the applicant before the deadline. Applicants admitted by more than one institution confirm
 * this one with the code NACTVET sent them. Offers not answered in time lapse, releasing the place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferService {

    /** Wrong confirmation codes allowed before the offer must be confirmed through the admissions office. */
    public static final int MAX_CONFIRMATION_ATTEMPTS = 5;

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final ApplicationRepository applications;
    private final ApplicantQualificationRepository qualifications;
    private final ApplicantProfileService profiles;
    private final TenantRepository tenants;
    private final NactvetGateway nactvet;
    private final SmsSender sms;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    /** Issues the offer to a verified applicant; called within the verification transaction. */
    public void issue(Application app, String verificationNo, boolean multipleAdmission, String institutionName) {
        OffsetDateTime now = OffsetDateTime.now();
        app.setStatus(Status.OFFERED);
        app.setNactvetVerificationNo(verificationNo);
        app.setVerifiedAt(now);
        app.setMultipleAdmission(multipleAdmission);
        app.setVerificationReason(null);
        app.setOfferIssuedAt(now);
        app.setOfferExpiresAt(deadline(now, app.getWindow().getOfferAcceptanceDays()));
        app.setConfirmationAttempts((short) 0);
        String msg = "Congratulations! NACTVET has verified your admission to " + programme(app) + " at " + institutionName
                + ". Log in to accept your offer by " + DATE.format(app.getOfferExpiresAt().atZoneSameInstant(ZONE)) + ".";
        if (multipleAdmission) {
            msg += " You were admitted by more than one institution: use the confirmation code NACTVET sent you.";
        }
        sms.send(app.getApplicant().getPhone(), msg);
    }

    /** The offer runs until the end of the day, East Africa Time, {@code days} days from now. */
    static OffsetDateTime deadline(OffsetDateTime now, int days) {
        return now.atZoneSameInstant(ZONE).toLocalDate().plusDays(days).atTime(23, 59, 59).atZone(ZONE).toOffsetDateTime();
    }

    // ------------------------------------------------------------------ applicant actions

    /**
     * Accepts the offer. Wrong confirmation codes are counted even though the request fails, so the
     * transaction is not rolled back for business rule violations (nothing else is changed before they are thrown).
     */
    @Transactional(noRollbackFor = BusinessRuleException.class)
    public void accept(Long applicationId, String confirmationCode) {
        Application app = ownOffer(applicationId);
        OffsetDateTime now = OffsetDateTime.now();
        if (!app.isOfferOpen(now)) {
            throw new BusinessRuleException("OFFER_EXPIRED", "The deadline to accept this offer has passed. Please contact the admissions office.");
        }
        if (app.isMultipleAdmission()) {
            if (app.getConfirmationAttempts() >= MAX_CONFIRMATION_ATTEMPTS) {
                throw new BusinessRuleException("CONFIRMATION_LOCKED",
                        "Too many wrong confirmation codes. Please visit or call the admissions office to confirm your admission.");
            }
            if (confirmationCode == null || confirmationCode.isBlank()) {
                throw new BusinessRuleException("CONFIRMATION_CODE_REQUIRED",
                        "You were admitted by more than one institution. Enter the confirmation code NACTVET sent you by SMS.");
            }
            Tenant tenant = currentTenant();
            String index = cseeIndex(app.getApplicant()).orElse("");
            if (!nactvet.confirmAdmission(tenant.getNactvetRegNo(), index, confirmationCode)) {
                app.setConfirmationAttempts((short) (app.getConfirmationAttempts() + 1));
                int left = MAX_CONFIRMATION_ATTEMPTS - app.getConfirmationAttempts();
                audit.record("OFFER_CONFIRMATION_FAILED", "Application", app.getId(), Map.of("attempt", app.getConfirmationAttempts()));
                throw new BusinessRuleException("INVALID_CONFIRMATION_CODE", left > 0
                        ? "That confirmation code is not correct. " + left + " attempt(s) left."
                        : "That confirmation code is not correct. Please contact the admissions office to confirm your admission.");
            }
        }
        app.setStatus(Status.ACCEPTED);
        app.setOfferRespondedAt(now);
        audit.record("OFFER_ACCEPTED", "Application", app.getId(), Map.of("multipleAdmission", app.isMultipleAdmission()));
        sms.send(app.getApplicant().getPhone(), "You have accepted your admission to " + programme(app) + " at "
                + currentTenant().getName() + ". Your admission letter and joining instructions will be available in the portal.");
        events.publishEvent(new OfferAcceptedEvent(app.getTenantId(), app.getId(), app.getApplicant().getId(),
                app.getSelectedWindowProgramme().getId()));
    }

    @Transactional
    public void decline(Long applicationId, String reason) {
        Application app = ownOffer(applicationId);
        app.setStatus(Status.DECLINED);
        app.setOfferRespondedAt(OffsetDateTime.now());
        app.setOfferDeclineReason(reason == null || reason.isBlank() ? null : reason.trim());
        audit.record("OFFER_DECLINED", "Application", app.getId(),
                Map.of("reason", app.getOfferDeclineReason() == null ? "" : app.getOfferDeclineReason()));
        sms.send(app.getApplicant().getPhone(), "You have declined your admission offer to " + programme(app)
                + ". Thank you for letting us know.");
    }

    /** Closes offers whose deadline has passed in the current institution; returns how many lapsed. */
    @Transactional
    public int lapseExpired() {
        List<Application> expired = applications.findByStatusAndOfferExpiresAtBefore(Status.OFFERED, OffsetDateTime.now());
        for (Application app : expired) {
            app.setStatus(Status.OFFER_LAPSED);
            audit.recordAs(null, "System", "OFFER_LAPSED", "Application", app.getId(), Map.of("expiredAt", app.getOfferExpiresAt().toString()));
            sms.send(app.getApplicant().getPhone(), "Your admission offer to " + programme(app)
                    + " has lapsed because it was not accepted by the deadline. Contact the admissions office if you need help.");
        }
        if (!expired.isEmpty()) {
            log.info("Closed {} expired admission offer(s)", expired.size());
        }
        return expired.size();
    }

    /** Confirmation code shown on screen in development (simulated NACTVET only). */
    @Transactional(readOnly = true)
    public Optional<String> developmentConfirmationCode(Application app) {
        if (!app.isMultipleAdmission() || app.getStatus() != Status.OFFERED) return Optional.empty();
        return cseeIndex(app.getApplicant()).flatMap(nactvet::developmentConfirmationCode);
    }

    // ------------------------------------------------------------------ helpers

    private Application ownOffer(Long applicationId) {
        Applicant me = profiles.currentApplicant();
        Application app = applications.findById(applicationId)
                .filter(a -> a.getApplicant().getId().equals(me.getId()))
                .orElseThrow(() -> new NotFoundException("Application", applicationId));
        if (app.getStatus() != Status.OFFERED) {
            throw new BusinessRuleException("NO_OPEN_OFFER", "There is no open admission offer on this application");
        }
        return app;
    }

    private Optional<String> cseeIndex(Applicant applicant) {
        return qualifications.findByApplicantIdIn(List.of(applicant.getId())).stream()
                .filter(q -> q.getType() == ApplicantQualification.Type.CSEE && q.isVerified() && q.getIndexNumber() != null)
                .max(Comparator.comparing((ApplicantQualification q) -> q.getExamYear() == null ? 0 : q.getExamYear()))
                .map(ApplicantQualification::getIndexNumber);
    }

    private Tenant currentTenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }

    private static String programme(Application app) {
        return app.getSelectedWindowProgramme().getProgramme().getName();
    }
}
