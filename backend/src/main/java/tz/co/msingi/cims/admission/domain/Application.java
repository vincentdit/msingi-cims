package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** One applicant's application in one admission window (BP-02). */
@Getter
@Setter
@Entity
@Table(name = "applications")
public class Application extends BaseEntity {

    public enum Status {
        DRAFT, PAYMENT_PENDING, SUBMITTED, UNDER_REVIEW, SELECTED, NOT_SELECTED, WITHDRAWN,
        PENDING_VERIFICATION, QUERIED, NACTVET_REJECTED, OFFERED, ACCEPTED, DECLINED, OFFER_LAPSED;

        /** Statuses in which the applicant holds a place in the selected programme (BP-04: other outcomes release it). */
        public static final Set<Status> HOLDS_PLACE = EnumSet.of(SELECTED, PENDING_VERIFICATION, QUERIED, OFFERED, ACCEPTED);
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id")
    private Applicant applicant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private AdmissionWindow window;

    private String referenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private Long feeInvoiceId;

    @Column(nullable = false)
    private boolean feePaid;

    private OffsetDateTime submittedAt;

    /** Programme the applicant was selected for, set when the Admissions Committee approves a round. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_window_programme_id")
    private WindowProgramme selectedWindowProgramme;

    private Long selectionRoundId;

    // ---- NACTVET verification and offer (BP-04)
    private String verificationRemarks;
    private String verificationReason;
    private String nactvetVerificationNo;
    private OffsetDateTime verifiedAt;

    @Column(nullable = false)
    private boolean multipleAdmission;

    private OffsetDateTime offerIssuedAt;
    private OffsetDateTime offerExpiresAt;
    private OffsetDateTime offerRespondedAt;
    private String offerDeclineReason;

    @Column(nullable = false)
    private short confirmationAttempts;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("choiceOrder")
    private List<ApplicationChoice> choices = new ArrayList<>();

    public boolean isOfferOpen(OffsetDateTime now) {
        return status == Status.OFFERED && offerExpiresAt != null && now.isBefore(offerExpiresAt);
    }

    public boolean isEditable() {
        return status == Status.DRAFT || status == Status.PAYMENT_PENDING;
    }
}
