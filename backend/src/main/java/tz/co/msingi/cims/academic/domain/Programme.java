package tz.co.msingi.cims.academic.domain;

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
import tz.co.msingi.cims.common.BaseEntity;

/** An NVA (Levels I–III) or NTA (Levels 4–8) programme accredited by NACTVET. */
@Getter
@Setter
@Entity
@Table(name = "programmes")
public class Programme extends BaseEntity {

    public enum AwardFamily { NVA, NTA }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false)
    private String code;

    private String nactvetCode;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private AwardFamily awardFamily;

    @Column(nullable = false)
    private short awardLevel;

    @Column(nullable = false)
    private String awardName;

    @Column(nullable = false)
    private short durationSemesters;

    private Integer accreditedCapacity;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /** Standard award names in the TVET Qualifications Framework. */
    public static String defaultAwardName(AwardFamily family, int level) {
        if (family == AwardFamily.NVA) {
            return "National Vocational Award Level " + "I".repeat(Math.max(1, Math.min(level, 3)));
        }
        return switch (level) {
            case 4 -> "Basic Technician Certificate (NTA Level 4)";
            case 5 -> "Technician Certificate (NTA Level 5)";
            case 6 -> "Ordinary Diploma (NTA Level 6)";
            case 7 -> "Higher Diploma (NTA Level 7)";
            case 8 -> "Bachelor Degree (NTA Level 8)";
            default -> "NTA Level " + level;
        };
    }
}
