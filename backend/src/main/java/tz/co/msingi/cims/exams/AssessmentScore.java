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
import java.time.OffsetDateTime;

/** A student's score in one assessment component, or absent. */
@Getter
@Setter
@Entity
@Table(name = "assessment_scores")
public class AssessmentScore extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id")
    private AssessmentComponent component;

    @Column(name = "module_registration_id", nullable = false)
    private Long moduleRegistrationId;

    private BigDecimal score;

    @Column(nullable = false)
    private boolean absent;

    private String enteredByName;

    @Column(nullable = false)
    private OffsetDateTime enteredAt = OffsetDateTime.now();
}
