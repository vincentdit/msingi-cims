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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The class result sheet of a programme and year of study for a semester (BP-08 steps 6-9): computed by the Examinations
 * Officer, recommended by the Academic Committee, confirmed by the Governing Board (results locked), then published.
 */
@Getter
@Setter
@Entity
@Table(name = "result_sheets")
public class ResultSheet extends BaseEntity {

    public enum Status { COMPUTED, RECOMMENDED, CONFIRMED, PUBLISHED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    @Column(nullable = false)
    private short yearOfStudy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "regulation_set_id")
    private RegulationSet regulationSet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.COMPUTED;

    private String computedByName;
    private OffsetDateTime computedAt;
    private Long recommendedBy;
    private String recommendedByName;
    private OffsetDateTime recommendedAt;
    private String committeeNote;
    private String confirmedByName;
    private OffsetDateTime confirmedAt;
    private String publishedByName;
    private OffsetDateTime publishedAt;
    private String returnedNote;
    private String returnedByName;
    private OffsetDateTime returnedAt;

    @OneToMany(mappedBy = "sheet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SemesterResult> results = new ArrayList<>();
}
