package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Intake;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** An admission window for an academic year and intake (BP-01). */
@Getter
@Setter
@Entity
@Table(name = "admission_windows")
public class AdmissionWindow extends BaseEntity {

    public enum Status { DRAFT, PUBLISHED, CLOSED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intake_id")
    private Intake intake;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private OffsetDateTime opensAt;

    @Column(nullable = false)
    private OffsetDateTime closesAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal applicationFee = BigDecimal.ZERO;

    @Column(nullable = false)
    private short maxChoices = 3;

    /** Days a verified applicant has to accept the offer (BP-04). */
    @Column(nullable = false)
    private short offerAcceptanceDays = 14;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(columnDefinition = "text")
    private String instructions;

    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<WindowProgramme> programmes = new ArrayList<>();

    public boolean isOpen(OffsetDateTime now) {
        return status == Status.PUBLISHED && !now.isBefore(opensAt) && now.isBefore(closesAt);
    }
}
