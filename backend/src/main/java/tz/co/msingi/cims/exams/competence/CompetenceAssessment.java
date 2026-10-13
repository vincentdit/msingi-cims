package tz.co.msingi.cims.exams.competence;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One attempt at one task by one trainee: the outcome, scores, date, assessor and evidence (FR-EXM-09). */
@Getter
@Setter
@Entity
@Table(name = "competence_assessments")
public class CompetenceAssessment extends TenantScopedEntity {

    public enum Outcome { COMPETENT, NOT_YET_COMPETENT }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    private CompetenceTask task;

    @Column(name = "module_registration_id", nullable = false)
    private Long moduleRegistrationId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private short attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private BigDecimal practicalScore;
    private BigDecimal theoryScore;

    @Column(nullable = false)
    private LocalDate assessedOn;

    @Column(nullable = false)
    private Long assessorId;

    @Column(nullable = false)
    private String assessorName;

    @Column(nullable = false)
    private String evidenceRef;

    private String comment;

    /** Set when the Head of Department verifies the unit; a verified attempt no longer changes. */
    @Column(nullable = false)
    private boolean verified;

    @Column(nullable = false)
    private OffsetDateTime recordedAt = OffsetDateTime.now();
}
