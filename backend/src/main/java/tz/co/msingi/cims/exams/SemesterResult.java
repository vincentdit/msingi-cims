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

/** A student's semester result on a class result sheet: GPA, CGPA and the progression decision (FR-EXM-06/07). */
@Getter
@Setter
@Entity
@Table(name = "semester_results")
public class SemesterResult extends TenantScopedEntity {

    public enum Decision { PASS, SUPPLEMENTARY, SPECIAL_EXAM, INCOMPLETE, REPEAT, DISCONTINUE, CARRY_OVER }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_sheet_id")
    private ResultSheet sheet;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private BigDecimal creditsRegistered;
    @Column(nullable = false)
    private BigDecimal creditsPassed;
    @Column(nullable = false)
    private BigDecimal creditsGraded;
    @Column(nullable = false)
    private BigDecimal pointsTotal;

    private BigDecimal gpa;
    private BigDecimal cgpa;

    @Column(nullable = false)
    private short modulesFailed;
    @Column(nullable = false)
    private short modulesIncomplete;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision proposedDecision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    private String decisionReason;
    private String classification;

    @Column(nullable = false)
    private boolean locked;
}
