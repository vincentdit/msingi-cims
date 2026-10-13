package tz.co.msingi.cims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A payment confirmed by GePG. gepgReceiptNo is unique, so a repeated notification is ignored. */
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment extends TenantScopedEntity {

    public enum Status { RECEIVED, ALLOCATED, UNMATCHED, REVERSED }

    /** GEPG: paid through GePG (reconciled); BANK: a bank remittance recorded by the Accountant; INTERNAL: not cash (a sponsor's share moved to a student, a waiver). */
    public enum Source { GEPG, BANK, INTERNAL }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(nullable = false)
    private String controlNo;

    @Column(nullable = false, unique = true)
    private String gepgReceiptNo;

    private String pspReceiptNo;
    private String pspName;
    private String channel;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    private String payerPhone;

    @Column(nullable = false)
    private OffsetDateTime paidAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RECEIVED;

    private String receiptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.GEPG;

    private String recordedByName;

    public boolean isCash() {
        return source != Source.INTERNAL;
    }

    /** Part of the payment beyond the invoice balance, held as credit (BP-06 exception: overpayment). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unallocatedAmount = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
