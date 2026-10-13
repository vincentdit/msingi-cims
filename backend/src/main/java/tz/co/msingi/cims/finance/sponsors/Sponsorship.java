package tz.co.msingi.cims.finance.sponsors;

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
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** A sponsor's share of one student's fees for an academic year: a percentage of the chosen fee items, up to an optional cap. */
@Getter
@Setter
@Entity
@Table(name = "sponsorships")
public class Sponsorship extends BaseEntity {

    public enum Status { ACTIVE, ENDED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sponsor_id")
    private Sponsor sponsor;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private Long academicYearId;

    @Column(nullable = false)
    private BigDecimal coveragePercent;

    private BigDecimal capAmount;

    private String feeItemCodes;

    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private String createdByName;

    public List<String> feeItems() {
        return feeItemCodes == null || feeItemCodes.isBlank() ? List.of() : Arrays.stream(feeItemCodes.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
