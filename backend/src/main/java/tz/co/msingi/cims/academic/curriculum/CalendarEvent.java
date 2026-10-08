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
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.LocalDate;

/** A key date in the academic calendar (FR-ACA-04). */
@Getter
@Setter
@Entity
@Table(name = "calendar_events")
public class CalendarEvent extends BaseEntity {

    public enum Kind { REGISTRATION, TEACHING, MARKS_ENTRY, EXAMINATIONS, RESULTS, HOLIDAY, GRADUATION, OTHER }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private LocalDate startDate;
    @Column(nullable = false)
    private LocalDate endDate;
}
