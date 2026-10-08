package tz.co.msingi.cims.academic.curriculum;

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
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;

/** A module (unit) in the catalogue; it can appear in several programmes' curricula (FR-ACA-02/03). */
@Getter
@Setter
@Entity
@Table(name = "modules")
public class Module extends BaseEntity {

    public enum AssessmentType { GRADED, COMPETENCE }

    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private BigDecimal credits;
    private Short ntaLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentType assessmentType = AssessmentType.GRADED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    private String description;
    private boolean active = true;
}
