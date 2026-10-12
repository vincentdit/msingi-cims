package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.util.ArrayList;
import java.util.List;

/** An office that clears finalists (FR-GRD-01): the student's department, finance (automatic) or an office with assigned officers. */
@Getter
@Setter
@Entity
@Table(name = "clearance_offices")
public class ClearanceOffice extends BaseEntity {

    public enum Kind { DEPARTMENT, FINANCE, STAFF }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String instructions;

    @Column(nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "office", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClearanceOfficeStaff> staff = new ArrayList<>();
}
