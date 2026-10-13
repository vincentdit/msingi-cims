package tz.co.msingi.cims.exams.appeal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/** A student's request to have a module result re-marked, or an appeal against it (FR-EXM-12, BP-11). */
@Getter
@Setter
@Entity
@Table(name = "result_appeals")
public class ResultAppeal extends BaseEntity {

    public enum Kind { REMARK, APPEAL }

    public enum Status { REQUESTED, ASSIGNED, MARKED, AMENDMENT_PENDING, CLOSED, REJECTED, WITHDRAWN }

    public enum Outcome { CHANGED, UNCHANGED }

    public static final Set<Status> OPEN = EnumSet.of(Status.REQUESTED, Status.ASSIGNED, Status.MARKED, Status.AMENDMENT_PENDING);

    @Column(nullable = false)
    private Long studentId;
    @Column(nullable = false)
    private Long moduleResultId;
    @Column(nullable = false)
    private Long offeringId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private BigDecimal fee = BigDecimal.ZERO;
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REQUESTED;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private BigDecimal originalCaTotal;
    private BigDecimal originalSeTotal;
    private BigDecimal originalTotal;
    private String originalGrade;

    private Long examinerUserId;
    private String examinerName;
    private String assignedByName;
    private OffsetDateTime assignedAt;

    private BigDecimal newCaTotal;
    private BigDecimal newSeTotal;
    private BigDecimal newTotal;
    private String newGrade;
    private String examinerComment;
    private OffsetDateTime markedAt;

    private Long amendmentId;
    private String decisionNote;
    private String decidedByName;
    private OffsetDateTime closedAt;

    @Column(nullable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();
}
