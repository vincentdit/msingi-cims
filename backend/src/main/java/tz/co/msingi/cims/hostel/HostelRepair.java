package tz.co.msingi.cims.hostel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** A repair reported in a hostel, by a student in the portal or by a warden, and tracked to completion (FR-HST-06). */
@Getter
@Setter
@Entity
@Table(name = "hostel_repairs")
public class HostelRepair extends BaseEntity {

    public enum Category { ELECTRICAL, PLUMBING, FURNITURE, STRUCTURE, CLEANING, SECURITY, OTHER }

    public enum Priority { NORMAL, URGENT }

    public enum Status { OPEN, IN_PROGRESS, DONE, CANCELLED }

    @Column(nullable = false)
    private String repairNo;

    @Column(nullable = false)
    private Long hostelId;

    private Long roomId;
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private String reportedByName;

    @Column(nullable = false)
    private OffsetDateTime reportedAt = OffsetDateTime.now();

    private String note;
    private String updatedByName;
    private OffsetDateTime resolvedAt;
}
