package tz.co.msingi.cims.exams;

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
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;

/** A part of the assessment plan: a CA item (test, assignment, practical) or the semester examination, with its weight. */
@Getter
@Setter
@Entity
@Table(name = "assessment_components")
public class AssessmentComponent extends TenantScopedEntity {

    public enum Category { CA, SE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private ModuleOffering offering;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    /** How much of the 100 marks this component carries. */
    @Column(nullable = false)
    private BigDecimal weight;

    /** What the component is marked out of (scaled to the weight). */
    @Column(nullable = false)
    private BigDecimal maxScore = new BigDecimal("100");

    @Column(nullable = false)
    private short sortOrder;
}
