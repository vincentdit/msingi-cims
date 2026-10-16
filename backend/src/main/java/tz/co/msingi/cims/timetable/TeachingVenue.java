package tz.co.msingi.cims.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A room where classes are taught and examinations are sat (FR-TCH-05, FR-EXM-15). */
@Getter
@Setter
@Entity
@Table(name = "teaching_venues")
public class TeachingVenue extends BaseEntity {

    public enum Kind { LECTURE_HALL, CLASSROOM, LABORATORY, COMPUTER_LAB, WORKSHOP, OTHER }

    private Long campusId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.CLASSROOM;

    @Column(nullable = false)
    private int capacity;

    /** Candidates seated for an examination (spaced out); the capacity when not set. */
    private Integer examCapacity;

    @Column(nullable = false)
    private boolean active = true;

    public int seatsForExams() {
        return examCapacity != null ? examCapacity : capacity;
    }
}
