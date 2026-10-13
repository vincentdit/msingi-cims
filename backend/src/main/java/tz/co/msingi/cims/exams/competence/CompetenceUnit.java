package tz.co.msingi.cims.exams.competence;

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

/** One module assessed by competence (NVA) in one semester (BP-09, FR-EXM-09). */
@Getter
@Setter
@Entity
@Table(name = "competence_units")
public class CompetenceUnit extends BaseEntity {

    public enum Status { DRAFT, SUBMITTED, VERIFIED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id")
    private Module module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String returnedNote;
    private String returnedByName;
    private OffsetDateTime returnedAt;
    private String submittedByName;
    private OffsetDateTime submittedAt;
    private String verifiedByName;
    private OffsetDateTime verifiedAt;

    @OneToMany(mappedBy = "unit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<CompetenceTask> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "unit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CompetenceAssessor> assessors = new ArrayList<>();
}
