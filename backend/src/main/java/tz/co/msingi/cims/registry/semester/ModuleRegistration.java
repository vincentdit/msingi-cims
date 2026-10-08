package tz.co.msingi.cims.registry.semester;

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
import tz.co.msingi.cims.academic.curriculum.CurriculumModule;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;

/** A module a student is registered for in a semester. */
@Getter
@Setter
@Entity
@Table(name = "module_registrations")
public class ModuleRegistration extends TenantScopedEntity {

    public enum Attempt { FIRST, SUPPLEMENTARY, CARRY_OVER, REPEAT }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_registration_id")
    private SemesterRegistration registration;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id")
    private Module module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CurriculumModule.Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Attempt attempt = Attempt.FIRST;

    @Column(nullable = false)
    private BigDecimal credits;
}
