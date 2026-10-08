package tz.co.msingi.cims.registry.semester;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.academic.curriculum.Curriculum;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A student's registration for a semester (FR-REG-03): their modules, the fee clearance when submitted, Registry's decision. */
@Getter
@Setter
@Entity
@Table(name = "semester_registrations")
public class SemesterRegistration extends BaseEntity {

    public enum Status { SUBMITTED, APPROVED, REJECTED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "curriculum_id")
    private Curriculum curriculum;

    @Column(nullable = false)
    private short yearOfStudy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SUBMITTED;

    private boolean late;
    @Column(nullable = false)
    private BigDecimal totalCredits = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> clearance;

    private boolean detailsConfirmed;
    @Column(nullable = false)
    private OffsetDateTime submittedAt;
    @Column(nullable = false)
    private String submittedByName;
    private OffsetDateTime decidedAt;
    private String decidedByName;
    private String decisionNote;

    @OneToMany(mappedBy = "registration", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ModuleRegistration> modules = new ArrayList<>();
}
