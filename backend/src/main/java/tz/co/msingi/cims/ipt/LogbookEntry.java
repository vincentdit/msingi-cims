package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A week of the student's electronic logbook, reviewed by the institution supervisor (FR-IPT-03). */
@Getter
@Setter
@Entity
@Table(name = "ipt_logbook_entries")
public class LogbookEntry extends BaseEntity {

    public enum Status { DRAFT, SUBMITTED, APPROVED, RETURNED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "placement_id")
    private IptPlacement placement;

    @Column(nullable = false)
    private short weekNo;

    @Column(nullable = false)
    private LocalDate weekStarting;

    @Column(nullable = false)
    private String activities;

    private String skills;
    private String challenges;

    @Column(precision = 5, scale = 1)
    private BigDecimal hours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private OffsetDateTime submittedAt;
    private String reviewerComment;
    private String reviewedByName;
    private OffsetDateTime reviewedAt;
}
