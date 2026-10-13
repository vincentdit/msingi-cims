package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** The institution's choices for the results workflow (BR-17). */
@Getter
@Setter
@Entity
@Table(name = "exam_settings")
public class ExamSettings extends BaseEntity {

    /** Days after results are published during which students may ask for a remark or appeal (BP-11); 0 closes appeals. */
    @Column(nullable = false)
    private short appealWindowDays = 14;

    /** Fee per module for a remark or appeal, invoiced with a GePG control number (0 = free). */
    @Column(nullable = false, precision = 12, scale = 2)
    private java.math.BigDecimal appealFee = java.math.BigDecimal.ZERO;

    /** Whether module results go to a Department Board after the Head of Department. */
    @Column(nullable = false)
    private boolean useDepartmentBoard = true;

    /** Minimum attendance to sit the semester examination (BRU-08). */
    @Column(nullable = false, precision = 5, scale = 2)
    private java.math.BigDecimal minAttendancePercent = new java.math.BigDecimal("75");

    /** Whether every continuous-assessment item must have a mark before the examination. */
    @Column(nullable = false)
    private boolean eligibilityRequiresCa = true;

    /** Whether the examination fees clearance gate applies. */
    @Column(nullable = false)
    private boolean eligibilityRequiresClearance = true;

    /** Students are warned once below the threshold after this many sessions. */
    @Column(nullable = false)
    private short attendanceAlertAfterSessions = 4;

    /** NVA: how many times a task may be assessed (the first attempt and re-assessments). */
    @Column(nullable = false)
    private short nvaMaxAttempts = 3;
}
