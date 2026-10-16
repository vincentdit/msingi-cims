package tz.co.msingi.cims.finance.adjustments;

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

/**
 * A waiver, discount or scholarship (each a credit note against an invoice) or a refund of a student's credit, approved at one or two
 * levels (FR-FIN-08/10).
 */
@Getter
@Setter
@Entity
@Table(name = "finance_adjustments")
public class FinanceAdjustment extends BaseEntity {

    public enum Kind { WAIVER, REFUND, DISCOUNT, SCHOLARSHIP }

    /** Kinds posted as a credit note against an invoice. */
    public boolean isCredit() {
        return kind != Kind.REFUND;
    }

    public enum Status { PENDING, ENDORSED, APPROVED, REJECTED, PAID }

    /** Where CIMS raised the request itself (the bursary still decides it); null when someone in the bursary recorded it. */
    public enum Origin { STATUS_CHANGE, HOSTEL_CHANGE, HOSTEL_CHECKOUT, HOSTEL_CANCEL }

    @Enumerated(EnumType.STRING)
    private Origin origin;

    private Long originId;

    @Column(nullable = false)
    private String adjustmentNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private Long studentId;

    private Long invoiceId;

    /** The discount or scholarship scheme the amount was worked out from, if any. */
    private Long schemeId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String evidenceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Long requestedById;

    @Column(nullable = false)
    private String requestedByName;

    @Column(nullable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    private Long firstById;
    private String firstByName;
    private OffsetDateTime firstAt;
    private Long secondById;
    private String secondByName;
    private OffsetDateTime secondAt;
    private String decisionNote;
    private String documentNo;
    private String paidReference;
    private OffsetDateTime paidAt;
}
