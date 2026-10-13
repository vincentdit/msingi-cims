package tz.co.msingi.cims.registry.status;

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
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.registry.Student;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/** A change to a student's status (BP-14): requested by Registry, recommended by the Head of Department, approved, then applied. */
@Getter
@Setter
@Entity
@Table(name = "student_status_changes")
public class StatusChange extends BaseEntity {

    /** What the change is, which statuses it applies to and the status it leads to. */
    public enum Type {
        POSTPONEMENT(Student.Status.POSTPONED, "postponed", EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE)),
        RESUMPTION(Student.Status.ACTIVE, "resumed", EnumSet.of(Student.Status.POSTPONED, Student.Status.SUSPENDED)),
        SUSPENSION(Student.Status.SUSPENDED, "suspended", EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE)),
        DISCONTINUATION(Student.Status.DISCONTINUED, "discontinued",
                EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE, Student.Status.POSTPONED, Student.Status.SUSPENDED)),
        WITHDRAWAL(Student.Status.WITHDRAWN, "withdrawn",
                EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE, Student.Status.POSTPONED, Student.Status.SUSPENDED)),
        DEATH(Student.Status.DECEASED, "recorded as deceased",
                EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE, Student.Status.POSTPONED, Student.Status.SUSPENDED));

        public final Student.Status target;
        public final String verb;
        public final Set<Student.Status> from;

        Type(Student.Status target, String verb, Set<Student.Status> from) {
            this.target = target;
            this.verb = verb;
            this.from = from;
        }

        /** Ends the student's time at the institution (their ID card stops being valid). */
        public boolean ends() {
            return this == DISCONTINUATION || this == WITHDRAWAL || this == DEATH;
        }
    }

    public enum State { REQUESTED, RECOMMENDED, APPROVED, APPLIED, REJECTED, CANCELLED }

    public static final Set<State> OPEN = EnumSet.of(State.REQUESTED, State.RECOMMENDED, State.APPROVED);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Student.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Student.Status toStatus;

    @Column(nullable = false)
    private String reason;

    private String reference;

    @Column(nullable = false)
    private LocalDate effectiveDate;

    private LocalDate untilDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private State state = State.REQUESTED;

    private Long requestedById;
    @Column(nullable = false)
    private String requestedByName;
    @Column(nullable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    private String recommendedByName;
    private OffsetDateTime recommendedAt;
    private String recommendationNote;

    private Long decidedById;
    private String decidedByName;
    private OffsetDateTime decidedAt;
    private String decisionNote;

    private OffsetDateTime appliedAt;
}
