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

/** A task a trainee must be assessed on for a unit, with the evidence it needs (BP-09 step 1). */
@Getter
@Setter
@Entity
@Table(name = "competence_tasks")
public class CompetenceTask extends TenantScopedEntity {

    public enum Kind { PRACTICAL, THEORY, WORKPLACE, TRADE_TEST, PROJECT }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id")
    private CompetenceUnit unit;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String evidenceRequired;

    @Column(nullable = false)
    private boolean required = true;

    @Column(nullable = false)
    private short sortOrder;
}
