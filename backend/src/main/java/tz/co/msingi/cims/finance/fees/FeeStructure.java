package tz.co.msingi.cims.finance.fees;

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
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fees for one programme and year of study in an academic year (FR-FIN-01). Prepared as a draft, approved by the
 * Bursar, then frozen: a change is a new version, and approving it supersedes the old one for new invoices only.
 */
@Getter
@Setter
@Entity
@Table(name = "fee_structures")
public class FeeStructure extends BaseEntity {

    public enum Status { DRAFT, APPROVED, SUPERSEDED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    @Column(nullable = false)
    private short yearOfStudy = 1;

    /** Only this intake (null = every intake), FR-FIN-01. */
    private Long intakeId;

    /** Only this student category (null = every category), FR-FIN-01. */
    private Long studentCategoryId;

    @Column(name = "structure_version", nullable = false)
    private short structureVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String notes;

    private Long createdBy;

    @Column(nullable = false)
    private String createdByName;

    private OffsetDateTime approvedAt;
    private Long approvedBy;
    private String approvedByName;
    private OffsetDateTime supersededAt;

    @OneToMany(mappedBy = "structure", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<FeeStructureLine> lines = new ArrayList<>();

    public BigDecimal total(boolean foreign) {
        return lines.stream().map(l -> l.amountFor(foreign)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
