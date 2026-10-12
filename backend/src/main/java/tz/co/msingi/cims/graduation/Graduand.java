package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A student on a graduation's list, the award as it stood when listed, and their award certificate (FR-GRD-03). */
@Getter
@Setter
@Entity
@Table(name = "graduands")
public class Graduand extends BaseEntity {

    @Column(name = "graduation_id", nullable = false)
    private Long graduationId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "programme_id", nullable = false)
    private Long programmeId;

    @Column(nullable = false)
    private String awardName;

    private String classification;

    @Column(precision = 4, scale = 2)
    private BigDecimal cgpa;

    @Column(precision = 6, scale = 1)
    private BigDecimal creditsEarned;

    @Column(nullable = false)
    private String completedSemester;

    private String certificateNo;
    private String verificationCode;
    private OffsetDateTime certificateIssuedAt;
    private String certificateIssuedBy;

    @Column(nullable = false)
    private int printCount;

    private OffsetDateTime lastPrintedAt;
}
