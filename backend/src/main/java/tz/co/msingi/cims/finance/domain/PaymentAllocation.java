package tz.co.msingi.cims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;

/** The part of a payment applied to one invoice line (FR-FIN-04). */
@Getter
@Setter
@Entity
@Table(name = "payment_allocations")
public class PaymentAllocation extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_line_id")
    private InvoiceLine invoiceLine;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
}
