package tz.co.msingi.cims.academic.curriculum;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** A version of a programme's curriculum: which modules are taken in which year and semester (FR-ACA-02). */
@Getter
@Setter
@Entity
@Table(name = "curricula")
public class Curriculum extends BaseEntity {

    public enum Status { DRAFT, APPROVED, RETIRED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    @Column(name = "curriculum_version", nullable = false)
    private short curriculumVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "effective_year_id")
    private AcademicYear effectiveYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String notes;
    private Long createdBy;
    @Column(nullable = false)
    private String createdByName;
    private OffsetDateTime approvedAt;
    private String approvedByName;
    private OffsetDateTime retiredAt;

    @OneToMany(mappedBy = "curriculum", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CurriculumModule> modules = new ArrayList<>();

    @OneToMany(mappedBy = "curriculum", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ElectiveRule> electiveRules = new ArrayList<>();
}
