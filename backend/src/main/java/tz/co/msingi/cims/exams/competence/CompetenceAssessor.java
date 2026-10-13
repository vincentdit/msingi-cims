package tz.co.msingi.cims.exams.competence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** An assessor allocated to a competence unit. */
@Getter
@Setter
@Entity
@Table(name = "competence_unit_assessors")
public class CompetenceAssessor extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id")
    private CompetenceUnit unit;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
