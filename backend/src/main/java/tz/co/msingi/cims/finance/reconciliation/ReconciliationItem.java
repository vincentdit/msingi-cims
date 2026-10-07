package tz.co.msingi.cims.finance.reconciliation;

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

/** One line of GePG's file (or one CIMS payment missing from it) and how it was reconciled. */
@Getter
@Setter
@Entity
@Table(name = "gepg_reconciliation_items")
public class ReconciliationItem extends TenantScopedEntity {

    public enum Kind { MATCHED, AMOUNT_MISMATCH, MISSING_IN_CIMS, MISSING_IN_GEPG, DUPLICATE, UNKNOWN_BILL }

    public enum Status { MATCHED, OPEN, RESOLVED }

    public enum Resolution { POSTED, NOTED, AUTO_MATCHED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_id")
    private GepgReconciliation reconciliation;

    private Integer lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String gepgReceiptNo;
    private String billId;
    private String controlNo;
    private String pspName;
    private String pspReceiptNo;
    private String channel;
    private String payerPhone;

    @Column(precision = 14, scale = 2)
    private BigDecimal gepgAmount;
    @Column(precision = 14, scale = 2)
    private BigDecimal cimsAmount;

    private OffsetDateTime paidAt;
    private Long paymentId;

    @Column(nullable = false)
    private boolean paidDateDiffers;

    @Enumerated(EnumType.STRING)
    private Resolution resolution;
    private String note;
    private OffsetDateTime resolvedAt;
    private String resolvedByName;
}
