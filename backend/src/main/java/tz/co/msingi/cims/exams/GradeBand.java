package tz.co.msingi.cims.exams;

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

/** One line of a grading scale: marks range, grade, grade points and whether it is a pass. */
@Getter
@Setter
@Entity
@Table(name = "grade_bands")
public class GradeBand extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "regulation_set_id")
    private RegulationSet regulationSet;

    @Column(nullable = false)
    private String grade;
    @Column(nullable = false)
    private short minMark;
    @Column(nullable = false)
    private short maxMark;
    @Column(nullable = false)
    private BigDecimal points;
    private String remark;
    @Column(nullable = false)
    private boolean passing;
}
