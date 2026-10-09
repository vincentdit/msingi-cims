package tz.co.msingi.cims.exams;

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
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A change to a confirmed (locked) module result: requested with a reason, approved by someone else, then applied (BP-08 exception). */
@Getter
@Setter
@Entity
@Table(name = "result_amendments")
public class ResultAmendment extends TenantScopedEntity {

    public enum Status { PENDING, APPROVED, REJECTED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_result_id")
    private ModuleResult moduleResult;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    private BigDecimal oldCaTotal;
    private BigDecimal oldSeTotal;
    private BigDecimal oldTotal;
    private String oldGrade;
    private String oldRemark;
    @Column(nullable = false)
    private BigDecimal newCaTotal;
    @Column(nullable = false)
    private BigDecimal newSeTotal;
    private BigDecimal newTotal;
    private String newGrade;
    private String newRemark;

    @Column(nullable = false)
    private String reason;
    private String evidenceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private Long requestedBy;
    @Column(nullable = false)
    private String requestedByName;
    @Column(nullable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();
    private String decidedByName;
    private OffsetDateTime decidedAt;
    private String decisionNote;
}
