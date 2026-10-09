package tz.co.msingi.cims.exams;

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
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A module taught in a semester (FR-TCH-01): its lecturers, its assessment plan (FR-EXM-01) and where its results are in the
 * approval route: lecturer → Head of Department → Department Board (BP-08 steps 1-5).
 */
@Getter
@Setter
@Entity
@Table(name = "module_offerings")
public class ModuleOffering extends BaseEntity {

    public enum Status { DRAFT, SUBMITTED, HOD_APPROVED, BOARD_APPROVED }

    /** Supplementary and special examination marks: lecturer → Head of Department. */
    public enum ResitStatus { DRAFT, SUBMITTED, APPROVED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id")
    private Module module;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "regulation_set_id")
    private RegulationSet regulationSet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String returnedNote;
    private String returnedByName;
    private OffsetDateTime returnedAt;
    private String submittedByName;
    private OffsetDateTime submittedAt;
    private String hodByName;
    private OffsetDateTime hodAt;
    private String boardByName;
    private OffsetDateTime boardAt;

    @Enumerated(EnumType.STRING)
    private ResitStatus resitStatus;
    private String resitSubmittedByName;
    private OffsetDateTime resitSubmittedAt;
    private String resitApprovedByName;
    private OffsetDateTime resitApprovedAt;
    private String resitReturnedNote;

    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<AssessmentComponent> components = new ArrayList<>();

    @OneToMany(mappedBy = "offering", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OfferingStaff> staff = new ArrayList<>();
}
