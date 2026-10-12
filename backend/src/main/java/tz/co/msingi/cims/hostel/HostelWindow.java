package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.LocalDate;

/** A hostel application window for an academic year, with the allocation priority rules (FR-HST-02/03). */
@Getter
@Setter
@Entity
@Table(name = "hostel_windows")
public class HostelWindow extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate opensOn;

    @Column(nullable = false)
    private LocalDate closesOn;

    private LocalDate occupancyStarts;
    private LocalDate occupancyEnds;

    @Column(nullable = false)
    private short paymentDays = 14;

    @Column(nullable = false)
    private boolean firstYearsFirst = true;

    @Column(nullable = false)
    private boolean specialNeedsFirst = true;

    @Column(nullable = false)
    private boolean paidFeesFirst = true;

    @Column(nullable = false, updatable = false)
    private String createdByName;
}
