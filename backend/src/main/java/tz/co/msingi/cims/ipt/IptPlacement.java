package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A student's placement: organisation, dates, workplace and institution supervisors, letter and result (FR-IPT-02). */
@Getter
@Setter
@Entity
@Table(name = "ipt_placements")
public class IptPlacement extends BaseEntity {

    public enum Status { REQUESTED, CONFIRMED, COMPLETED, CANCELLED, DECLINED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id")
    private IptPeriod period;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id")
    private PlacementOrganisation organisation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REQUESTED;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    private String unit;
    private String workplaceSupervisorName;
    private String workplaceSupervisorTitle;
    private String workplaceSupervisorPhone;
    private String workplaceSupervisorEmail;

    @Column(name = "institution_supervisor_id")
    private Long institutionSupervisorId;

    private String studentNote;
    private String decisionNote;
    private String letterNo;
    private OffsetDateTime letterIssuedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    private String confirmedByName;
    private OffsetDateTime confirmedAt;

    @Column(precision = 5, scale = 2)
    private BigDecimal totalMark;

    private OffsetDateTime transferredAt;

    public boolean live() {
        return status == Status.REQUESTED || status == Status.CONFIRMED || status == Status.COMPLETED;
    }
}
