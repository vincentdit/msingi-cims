package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** An organisation that takes students for field attachment, with its contact (FR-IPT-01). */
@Getter
@Setter
@Entity
@Table(name = "placement_organisations")
public class PlacementOrganisation extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String sector;
    private String region;
    private String district;
    private String address;
    private String contactName;
    private String contactTitle;
    private String contactPhone;
    private String contactEmail;
    private Integer capacity;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean proposedByStudent;
}
