package tz.co.msingi.cims.finance.fees;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;

/** What must be paid before a step (FR-FIN-06), e.g. registration: 50% of annual fees and the first instalment. */
@Getter
@Setter
@Entity
@Table(name = "clearance_gates")
public class ClearanceGate extends BaseEntity {

    public enum Gate { REGISTRATION, EXAMINATION, RESULTS, HOSTEL, GRADUATION }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Gate gate;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal minPercentPaid = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean firstInstalmentRequired;
}
