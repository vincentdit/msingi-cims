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
import tz.co.msingi.cims.common.TenantScopedEntity;

/** A module's place in a curriculum: year of study, semester, core or elective. */
@Getter
@Setter
@Entity
@Table(name = "curriculum_modules")
public class CurriculumModule extends TenantScopedEntity {

    public enum Category { CORE, ELECTIVE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "curriculum_id")
    private Curriculum curriculum;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id")
    private Module module;

    @Column(nullable = false)
    private short yearOfStudy;
    @Column(nullable = false)
    private short semesterNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;
}
