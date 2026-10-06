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
import java.util.List;

/** One applicant's application in one admission window (BP-02). */
@Getter
@Setter
@Entity
@Table(name = "applications")
public class Application extends BaseEntity {

    public enum Status { DRAFT, PAYMENT_PENDING, SUBMITTED, UNDER_REVIEW, SELECTED, NOT_SELECTED, WITHDRAWN }

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

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("choiceOrder")
    private List<ApplicationChoice> choices = new ArrayList<>();

    public boolean isEditable() {
        return status == Status.DRAFT || status == Status.PAYMENT_PENDING;
    }
}
