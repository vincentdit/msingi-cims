package tz.co.msingi.cims.timetable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.common.TenantScopedEntity;
import tz.co.msingi.cims.exams.ModuleOffering;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** An examination paper of a module offering (FR-EXM-15): when, where it is sat and who invigilates. */
@Getter
@Setter
@Entity
@Table(name = "exam_sittings")
public class ExamSitting extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private ModuleOffering offering;

    @Column(nullable = false)
    private String paper = "Paper 1";

    @Column(nullable = false)
    private LocalDate sittingDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private String note;

    /** The last change students should know about (shown as changed after publishing). */
    @Column(nullable = false)
    private java.time.OffsetDateTime changedAt = java.time.OffsetDateTime.now();

    @OneToMany(mappedBy = "sitting", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Venue> venues = new ArrayList<>();

    @OneToMany(mappedBy = "sitting", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Invigilator> invigilators = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "exam_sitting_venues")
    public static class Venue extends TenantScopedEntity {
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "sitting_id")
        private ExamSitting sitting;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "venue_id")
        private TeachingVenue venue;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "exam_invigilators")
    public static class Invigilator extends TenantScopedEntity {
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "sitting_id")
        private ExamSitting sitting;

        @Column(name = "user_id", nullable = false)
        private Long userId;
    }
}
