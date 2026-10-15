package tz.co.msingi.cims.academic.campus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A campus of the institution (FR-ACA-01). One is the main campus; programmes are taught at one or more campuses. */
@Getter
@Setter
@Entity
@Table(name = "campuses")
public class Campus extends BaseEntity {
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    private String region;
    private String district;
    private String address;
    @Column(nullable = false)
    private boolean main;
    @Column(nullable = false)
    private boolean active = true;
}
