package tz.co.msingi.cims.teaching;

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
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.exams.ModuleOffering;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A class meeting of a module offering and who attended it (FR-TCH-03). */
@Getter
@Setter
@Entity
@Table(name = "class_sessions")
public class ClassSession extends BaseEntity {

    public enum Kind { LECTURE, TUTORIAL, PRACTICAL, SEMINAR }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private ModuleOffering offering;

    @Column(nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private short durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.LECTURE;

    private String topic;

    @Column(nullable = false, updatable = false)
    private UUID clientRef;

    @Column(nullable = false)
    private Long recordedById;

    @Column(nullable = false)
    private String recordedByName;

    // ---- self check-in (FR-TCH-03): open while checkinOpenedAt is set and checkinClosedAt is not
    private String checkinSecret;
    private OffsetDateTime checkinOpenedAt;
    private OffsetDateTime checkinClosesAt;
    private Short checkinLateAfter;
    private OffsetDateTime checkinClosedAt;

    public boolean checkInOpen() {
        return checkinOpenedAt != null && checkinClosedAt == null;
    }

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AttendanceRecord> records = new ArrayList<>();
}
