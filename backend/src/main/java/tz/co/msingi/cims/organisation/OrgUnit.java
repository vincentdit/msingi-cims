package tz.co.msingi.cims.organisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A unit of the institution's organisation structure: the board, an office, a directorate, faculty, department, unit, section or committee. */
@Getter
@Setter
@Entity
@Table(name = "org_units")
public class OrgUnit extends BaseEntity {

    public enum Kind { BOARD, COMMITTEE, OFFICE, DIRECTORATE, FACULTY, DEPARTMENT, UNIT, SECTION, CAMPUS }

    /** The unit it reports to; empty for the top of the structure. */
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String name;
    private String shortName;
    /** The academic department this unit is, when it is one (its programmes and staff in CIMS). */
    private Long departmentId;
    /** Advises the unit above rather than reporting in line (a board or committee drawn beside it). */
    private boolean advisory;
    private int sortOrder;
    private String description;
}
