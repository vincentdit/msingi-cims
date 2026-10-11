package tz.co.msingi.cims.teaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Whether a student may sit a module's semester examination (FR-EXM-04, BRU-08), from the latest list generated. */
@Getter
@Setter
@Entity
@Table(name = "exam_eligibility")
public class ExamEligibility extends BaseEntity {

    @Column(name = "offering_id", nullable = false)
    private Long offeringId;

    @Column(name = "module_registration_id", nullable = false, updatable = false)
    private Long moduleRegistrationId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    private int sessionsCounted;
    private int sessionsAttended;

    @Column(precision = 5, scale = 1)
    private BigDecimal attendancePercent;

    private boolean attendanceMet;
    private boolean caMet;
    private boolean clearanceMet;
    private boolean rulesMet;
    private boolean exempted;
    private boolean eligible;

    /** Why a rule was not met, in words a student understands. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_detail", nullable = false)
    private List<String> detail = new ArrayList<>();

    @Column(nullable = false)
    private String generatedByName;

    @Column(nullable = false)
    private OffsetDateTime generatedAt;
}
