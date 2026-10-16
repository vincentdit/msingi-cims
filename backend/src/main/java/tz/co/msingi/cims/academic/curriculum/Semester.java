package tz.co.msingi.cims.academic.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.LocalDate;

/** A semester of an academic year, with its registration period (FR-ACA-04). */
@Getter
@Setter
@Entity
@Table(name = "semesters")
public class Semester extends BaseEntity {

    public enum Window { NOT_OPEN, OPEN, LATE, CLOSED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @Column(nullable = false)
    private short semesterNo;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private LocalDate startDate;
    @Column(nullable = false)
    private LocalDate endDate;
    @Column(nullable = false)
    private LocalDate registrationOpens;
    @Column(nullable = false)
    private LocalDate registrationCloses;
    private LocalDate lateRegistrationCloses;
    /** Charged to a student who registers in the late period (BP-05); zero for none. */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal lateRegistrationFee = java.math.BigDecimal.ZERO;
    /** From this date lecturers may enter semester-examination marks (FR-EXM-02). */
    private LocalDate marksEntryOpens;
    /** After this date no marks can be entered. */
    private LocalDate marksEntryCloses;

    public Window window(LocalDate today) {
        if (today.isBefore(registrationOpens)) return Window.NOT_OPEN;
        if (!today.isAfter(registrationCloses)) return Window.OPEN;
        if (lateRegistrationCloses != null && !today.isAfter(lateRegistrationCloses)) return Window.LATE;
        return Window.CLOSED;
    }
}
