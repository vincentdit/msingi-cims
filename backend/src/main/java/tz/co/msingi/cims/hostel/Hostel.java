package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A hostel with its gender designation (FR-HST-01). */
@Getter
@Setter
@Entity
@Table(name = "hostels")
public class Hostel extends BaseEntity {

    public enum Gender { FEMALE, MALE, MIXED }

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    private String location;

    @Column(nullable = false)
    private boolean active = true;
}
