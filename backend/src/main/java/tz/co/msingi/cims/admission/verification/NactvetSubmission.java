package tz.co.msingi.cims.admission.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** A batch of selected applicants sent to NACTVET for admission verification (BP-04). */
@Getter
@Setter
@Entity
@Table(name = "nactvet_submissions")
public class NactvetSubmission extends BaseEntity {

    public enum Channel { API, FILE }

    public enum Status { SUBMITTED, COMPLETED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private AdmissionWindow window;

    @Column(nullable = false)
    private short batchNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SUBMITTED;

    private String externalRef;

    @Column(nullable = false)
    private OffsetDateTime submittedAt;

    @Column(nullable = false)
    private Long submittedBy;

    @Column(nullable = false)
    private String submittedByName;

    private OffsetDateTime lastOutcomeAt;
    private OffsetDateTime completedAt;
}
