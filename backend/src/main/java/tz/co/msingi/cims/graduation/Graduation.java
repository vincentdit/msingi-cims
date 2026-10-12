package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A graduation (FR-GRD-02): its graduand list, Governing Board approval and NACTVET submission. */
@Getter
@Setter
@Entity
@Table(name = "graduations")
public class Graduation extends BaseEntity {

    public enum Status { DRAFT, SUBMITTED, APPROVED }

    public enum NactvetStatus { SUBMITTED, ACCEPTED, QUERIED }

    @Column(nullable = false)
    private String name;

    private LocalDate ceremonyDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String generatedByName;
    private OffsetDateTime generatedAt;
    private Long submittedById;
    private String submittedByName;
    private OffsetDateTime submittedAt;
    private String approvedByName;
    private OffsetDateTime approvedAt;
    private String boardMinuteRef;
    private String returnedNote;
    private String returnedByName;

    @Enumerated(EnumType.STRING)
    private NactvetStatus nactvetStatus;
    private String nactvetReference;
    private String nactvetNote;
    private OffsetDateTime nactvetSubmittedAt;

    @Column(nullable = false, updatable = false)
    private String createdByName;
}
