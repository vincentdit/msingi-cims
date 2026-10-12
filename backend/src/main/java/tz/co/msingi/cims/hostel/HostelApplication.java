package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** A student's hostel application with up to three hostel preferences (FR-HST-02). */
@Getter
@Setter
@Entity
@Table(name = "hostel_applications")
public class HostelApplication extends BaseEntity {

    public enum Status { SUBMITTED, ALLOCATED, WAITLISTED, RELEASED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private HostelWindow window;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "preference1_id")
    private Long preference1Id;
    @Column(name = "preference2_id")
    private Long preference2Id;
    @Column(name = "preference3_id")
    private Long preference3Id;

    @Enumerated(EnumType.STRING)
    private HostelRoom.RoomType roomType;

    @Column(nullable = false)
    private boolean specialNeeds;

    private String specialNeedsNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SUBMITTED;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();

    private OffsetDateTime cancelledAt;
}
