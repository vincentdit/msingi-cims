package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;

/** What a bed costs for an academic year (FR-HST-01 fee category). */
@Getter
@Setter
@Entity
@Table(name = "hostel_fee_categories")
public class HostelFeeCategory extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean active = true;
}
