package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One of the three IPT marks (workplace supervisor, institution supervisor, logbook), out of 100. */
@Getter
@Setter
@Entity
@Table(name = "ipt_assessments")
public class IptAssessment extends BaseEntity {

    public enum Kind { WORKPLACE, INSTITUTION, LOGBOOK }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "placement_id")
    private IptPlacement placement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    private String comments;

    @Column(nullable = false)
    private String assessedByName;

    @Column(nullable = false)
    private OffsetDateTime assessedAt = OffsetDateTime.now();
}
