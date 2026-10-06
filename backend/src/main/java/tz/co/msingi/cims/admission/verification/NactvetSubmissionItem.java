package tz.co.msingi.cims.admission.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;

/** One applicant in a NACTVET submission, with NACTVET's outcome. Kept as history across resubmissions. */
@Getter
@Setter
@Entity
@Table(name = "nactvet_submission_items")
public class NactvetSubmissionItem extends TenantScopedEntity {

    public enum Outcome { PENDING, VERIFIED, QUERIED, REJECTED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id")
    private NactvetSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_programme_id")
    private WindowProgramme windowProgramme;

    @Column(nullable = false)
    private String indexNumber;

    @Column(nullable = false)
    private String programmeCode;

    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome = Outcome.PENDING;

    private String reason;
    private String verificationNo;

    @Column(nullable = false)
    private boolean multipleAdmission;

    private OffsetDateTime outcomeAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
