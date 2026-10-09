package tz.co.msingi.cims.exams;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Examination regulations for a range of award levels (FR-ACA-05, Document 03 section 5): the grading scale, CA/SE weights and
 * pass marks, GPA rounding, classification and progression thresholds. Versioned by the academic year they apply from.
 */
@Getter
@Setter
@Entity
@Table(name = "regulation_sets")
public class RegulationSet extends BaseEntity {

    public enum Rounding { ROUND, TRUNCATE }

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Programme.AwardFamily awardFamily;

    @Column(nullable = false)
    private short levelFrom;
    @Column(nullable = false)
    private short levelTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "effective_year_id")
    private AcademicYear effectiveYear;

    @Column(nullable = false)
    private BigDecimal caWeight;
    @Column(nullable = false)
    private BigDecimal seWeight;
    @Column(nullable = false)
    private BigDecimal caPassPercent = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal sePassPercent = BigDecimal.ZERO;
    @Column(nullable = false)
    private short gpaDecimals = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rounding gpaRounding = Rounding.ROUND;

    @Column(nullable = false)
    private BigDecimal suppMaxFailedPercent = new BigDecimal("50");
    private String suppMaxGrade;
    private BigDecimal discontinueGpaBelow;
    private String notes;
    private String createdByName;

    @OneToMany(mappedBy = "regulationSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minMark DESC")
    private List<GradeBand> gradeBands = new ArrayList<>();

    @OneToMany(mappedBy = "regulationSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minGpa DESC")
    private List<ClassificationBand> classifications = new ArrayList<>();

    public boolean covers(Programme.AwardFamily family, int level) {
        return awardFamily == family && level >= levelFrom && level <= levelTo;
    }
}
