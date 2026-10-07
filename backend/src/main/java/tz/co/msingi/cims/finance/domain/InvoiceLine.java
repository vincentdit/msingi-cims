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

@Getter
@Setter
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(nullable = false)
    private String feeItemCode;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    private Long feeItemId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** Allocation order within an instalment: lower is paid first (from the fee item). */
    @Column(nullable = false)
    private short priority = 100;

    @Column(nullable = false)
    private short instalment = 1;

    public BigDecimal balance() {
        return amount.subtract(paidAmount);
    }
}
