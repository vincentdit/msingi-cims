package tz.co.msingi.cims.finance.fees;

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
@Table(name = "fee_structure_lines")
public class FeeStructureLine extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_structure_id")
    private FeeStructure structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_item_id")
    private FeeItem feeItem;

    /** Amount for Tanzanian students. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Amount for other nationalities; null means the same amount. */
    @Column(precision = 14, scale = 2)
    private BigDecimal foreignAmount;

    @Column(nullable = false)
    private short instalment = 1;

    public BigDecimal amountFor(boolean foreign) {
        return foreign && foreignAmount != null ? foreignAmount : amount;
    }
}
