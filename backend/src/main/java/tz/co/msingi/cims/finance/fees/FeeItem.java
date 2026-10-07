package tz.co.msingi.cims.finance.fees;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** Something the institution bills for (FR-FIN-01). */
@Getter
@Setter
@Entity
@Table(name = "fee_items")
public class FeeItem extends BaseEntity {

    public enum Category { TUITION, REGISTRATION, EXAMINATION, ACCOMMODATION, CAUTION, STUDENT_UNION, OTHER }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    /** GePG revenue (GFS) code. */
    private String gfsCode;

    /** Lower is paid first when a payment is allocated (FR-FIN-04). */
    @Column(nullable = false)
    private short allocationPriority = 100;

    @Column(nullable = false)
    private boolean active = true;
}
