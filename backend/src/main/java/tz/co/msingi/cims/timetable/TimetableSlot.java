package tz.co.msingi.cims.timetable;

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
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.teaching.ClassSession;

import java.time.LocalTime;

/** A weekly class of a module offering (FR-TCH-05): day, time, venue and lecturer (any of the offering's lecturers when empty). */
@Getter
@Setter
@Entity
@Table(name = "timetable_slots")
public class TimetableSlot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private ModuleOffering offering;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassSession.Kind kind = ClassSession.Kind.LECTURE;

    /** ISO day of the week: 1 Monday … 7 Sunday. */
    @Column(nullable = false)
    private short dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id")
    private TeachingVenue venue;

    private Long lecturerUserId;

    /** A part of the class taught separately, such as "Practical group A". */
    private String groupLabel;

    private String note;

    /** The last change students should know about (shown as changed after publishing). */
    @Column(nullable = false)
    private java.time.OffsetDateTime changedAt = java.time.OffsetDateTime.now();
}
