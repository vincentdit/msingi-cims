package tz.co.msingi.cims.admission.selection;

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

/** A selection round: proposed by the Admissions Office, approved by the Admissions Committee. */
@Getter
@Setter
@Entity
@Table(name = "selection_rounds")
public class SelectionRound extends BaseEntity {

    public enum Status { PROPOSED, APPROVED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private AdmissionWindow window;

    @Column(nullable = false)
    private short roundNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PROPOSED;

    @Column(nullable = false)
    private OffsetDateTime proposedAt;

    @Column(nullable = false)
    private Long proposedBy;

    @Column(nullable = false)
    private String proposedByName;

    private OffsetDateTime approvedAt;
    private Long approvedBy;
    private String approvedByName;
    private String committeeMinuteRef;

    @Column(columnDefinition = "text")
    private String notes;
}
