package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A bed allocated to a student for a window, its invoice, and check-in and check-out (FR-HST-03/04/05). */
@Getter
@Setter
@Entity
@Table(name = "hostel_allocations")
public class HostelAllocation extends BaseEntity {

    public enum Status { ALLOCATED, CHECKED_IN, CHECKED_OUT, RELEASED, CANCELLED }

    public enum Method { AUTO, MANUAL }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private HostelApplication application;

    @Column(name = "window_id", nullable = false, updatable = false)
    private Long windowId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id")
    private HostelBed bed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ALLOCATED;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;

    private Long invoiceId;

    @Column(nullable = false)
    private LocalDate payBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;

    @Column(nullable = false)
    private String allocatedByName;

    @Column(nullable = false)
    private OffsetDateTime allocatedAt = OffsetDateTime.now();

    private OffsetDateTime checkedInAt;
    private String checkedInByName;
    private String checkInCondition;
    private OffsetDateTime checkedOutAt;
    private String checkedOutByName;
    private String checkOutCondition;
    private String damages;

    @Column(precision = 12, scale = 2)
    private BigDecimal damageCharge;

    private Long damageInvoiceId;
    private OffsetDateTime releasedAt;
    private String releaseReason;

    /** The refund of the unused part of a paid fee raised at check-out or cancellation, waiting for or decided by the bursary. */
    private Long refundAdjustmentId;

    public boolean active() {
        return status == Status.ALLOCATED || status == Status.CHECKED_IN;
    }
}
