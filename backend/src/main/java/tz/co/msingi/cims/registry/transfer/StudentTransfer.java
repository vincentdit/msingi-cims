package tz.co.msingi.cims.registry.transfer;

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
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.registry.Student;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** A programme and/or campus transfer (FR-REG-05). Kept for good as the student's transfer history. */
@Getter
@Setter
@Entity
@Table(name = "student_transfers")
public class StudentTransfer extends BaseEntity {

    public enum Kind { PROGRAMME, CAMPUS, BOTH }

    public enum State { REQUESTED, RECOMMENDED, APPROVED, REJECTED, CANCELLED }

    public static final List<State> OPEN = List.of(State.REQUESTED, State.RECOMMENDED);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_programme_id")
    private Programme fromProgramme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_programme_id")
    private Programme toProgramme;

    private Long fromCampusId;
    private Long toCampusId;

    @Column(nullable = false)
    private short fromYearOfStudy;
    @Column(nullable = false)
    private short toYearOfStudy;

    @Column(nullable = false)
    private String reason;
    @Column(nullable = false)
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state = State.REQUESTED;

    @Column(nullable = false)
    private boolean requestedByStudent;
    private Long requestedById;
    @Column(nullable = false)
    private String requestedByName;
    @Column(nullable = false)
    private OffsetDateTime requestedAt;

    private String recommendedByName;
    private OffsetDateTime recommendedAt;
    private String recommendationNote;

    private Long decidedById;
    private String decidedByName;
    private OffsetDateTime decidedAt;
    private String decisionNote;
}
