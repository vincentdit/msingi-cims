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

/**
 * A supplementary (failed module, grade capped) or special (missed examination, not capped) examination in one module, and its
 * outcome (FR-EXM-08). Coursework is carried from the first attempt; the resit replaces the semester examination.
 */
@Getter
@Setter
@Entity
@Table(name = "resits")
public class Resit extends TenantScopedEntity {

    public enum Kind { SUPPLEMENTARY, SPECIAL }

    public enum Status { REGISTERED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ResitSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_result_id")
    private ModuleResult moduleResult;

    @Column(name = "offering_id", nullable = false)
    private Long offeringId;
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REGISTERED;

    @Column(nullable = false)
    private BigDecimal fee = BigDecimal.ZERO;
    private Long invoiceId;

    @Column(nullable = false)
    private OffsetDateTime registeredAt = OffsetDateTime.now();

    private BigDecimal score;
    @Column(nullable = false)
    private boolean absent;
    private BigDecimal caTotal;
    private BigDecimal seTotal;
    private BigDecimal total;
    private String grade;
    private BigDecimal gradePoints;

    @Enumerated(EnumType.STRING)
    private ModuleResult.Remark remark;
    private String failReason;
    @Column(nullable = false)
    private boolean capped;
    private String enteredByName;
    private OffsetDateTime enteredAt;
    @Column(nullable = false)
    private boolean locked;
}
