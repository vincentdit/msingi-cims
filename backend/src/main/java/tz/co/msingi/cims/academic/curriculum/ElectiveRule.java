package tz.co.msingi.cims.academic.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** How many electives a student takes in one year/semester of a curriculum. */
@Getter
@Setter
@Entity
@Table(name = "curriculum_elective_rules")
public class ElectiveRule extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "curriculum_id")
    private Curriculum curriculum;

    @Column(nullable = false)
    private short yearOfStudy;
    @Column(nullable = false)
    private short semesterNo;
    @Column(nullable = false)
    private short minElectives;
    @Column(nullable = false)
    private short maxElectives;
}
