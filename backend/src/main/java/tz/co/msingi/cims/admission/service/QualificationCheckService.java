package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationCheckRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationCheckView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationView;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Admissions Office checks qualifications applicants declare — NVA awards, other awards and NTA awards typed in —
 * against the certificates. A verified qualification counts for eligibility (and so for selection); one not accepted
 * shows the reason to the applicant, who is told by SMS. Results from NECTA and NACTVET are verified at source.
 */
@Service
@RequiredArgsConstructor
public class QualificationCheckService {

    private final ApplicantQualificationRepository qualifications;
    private final ApplicationRepository applications;
    private final Messaging messaging;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<QualificationCheckView> list(Long windowId, ApplicantQualification.CheckStatus status) {
        return qualifications.findForCheck(windowId, status).stream().map(q -> view(q, windowId)).toList();
    }

    @Transactional
    public QualificationCheckView decide(Long id, QualificationCheckRequest req) {
        ApplicantQualification q = qualifications.findById(id).orElseThrow(() -> new NotFoundException("Qualification", id));
        if (q.getSource() != ApplicantQualification.Source.MANUAL) {
            throw new BusinessRuleException("VERIFIED_AT_SOURCE", "Results from NECTA and NACTVET are verified at source and are not checked here");
        }
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (!req.verified() && note == null) {
            throw new BusinessRuleException("REASON_REQUIRED", "Give the reason the qualification is not accepted; the applicant will see it");
        }
        q.setCheckStatus(req.verified() ? ApplicantQualification.CheckStatus.VERIFIED : ApplicantQualification.CheckStatus.REJECTED);
        q.setVerified(req.verified());
        q.setVerifiedAt(OffsetDateTime.now());
        q.setCheckNote(note);
        q.setCheckedByName(CurrentUser.name());
        Map<String, Object> details = new HashMap<>();
        details.put("type", q.getType().name());
        details.put("number", q.getIndexNumber());
        details.put("verified", req.verified());
        if (note != null) details.put("note", note);
        audit.record(req.verified() ? "QUALIFICATION_VERIFIED" : "QUALIFICATION_REJECTED", "Applicant", q.getApplicant().getId(), details);
        if (!req.verified()) {
            messaging.event("QUALIFICATION_NOT_ACCEPTED", q.getApplicant().getPhone(), Map.of("award", label(q), "reason", note));
        }
        Long windowId = applications.findByApplicantIdOrderByCreatedAtDesc(q.getApplicant().getId()).stream()
                .findFirst().map(a -> a.getWindow().getId()).orElse(null);
        return view(q, windowId);
    }

    static String label(ApplicantQualification q) {
        return switch (q.getType()) {
            case NVA -> "NVA Level " + q.getAwardLevel() + " (" + q.getIndexNumber() + ")";
            case NTA -> "NTA Level " + q.getAwardLevel() + " (" + q.getIndexNumber() + ")";
            case OTHER -> q.getAwardName();
            default -> q.getType() + " " + q.getIndexNumber();
        };
    }

    private QualificationCheckView view(ApplicantQualification q, Long windowId) {
        Application app = applications.findByApplicantIdOrderByCreatedAtDesc(q.getApplicant().getId()).stream()
                .filter(a -> windowId == null || a.getWindow().getId().equals(windowId)).findFirst().orElse(null);
        return new QualificationCheckView(QualificationView.of(q), q.getApplicant().getId(), q.getApplicant().fullName(), q.getApplicant().getPhone(),
                app == null ? null : app.getId(), app == null ? null : app.getReferenceNo(), app == null ? null : app.getStatus().name());
    }
}
