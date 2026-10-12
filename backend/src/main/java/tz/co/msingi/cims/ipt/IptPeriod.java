package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.Semester;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** An IPT period of a semester and the IPT module whose marks it produces, with the weights of the three marks (BP-13). */
@Getter
@Setter
@Entity
@Table(name = "ipt_periods")
public class IptPeriod extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private Module module;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    private LocalDate requestsClose;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal workplaceWeight = new BigDecimal("40");

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal institutionWeight = new BigDecimal("30");

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal logbookWeight = new BigDecimal("30");

    @Column(nullable = false)
    private short minLogbookWeeks = 1;

    private OffsetDateTime resultsTransferredAt;
    private String resultsTransferredBy;

    @Column(nullable = false, updatable = false)
    private String createdByName;
}
