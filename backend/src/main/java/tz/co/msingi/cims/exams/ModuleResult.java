package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The computed result of one module registration (FR-EXM-03). Locked once the Governing Board confirms (BRU-13). */
@Getter
@Setter
@Entity
@Table(name = "module_results")
public class ModuleResult extends TenantScopedEntity {

    public enum Remark { PASS, FAIL, INCOMPLETE, ABSENT }

    @Column(name = "module_registration_id", nullable = false)
    private Long moduleRegistrationId;
    @Column(name = "offering_id", nullable = false)
    private Long offeringId;
    @Column(name = "student_id", nullable = false)
    private Long studentId;
    @Column(name = "regulation_set_id", nullable = false)
    private Long regulationSetId;

    private BigDecimal caTotal;
    private BigDecimal seTotal;
    private BigDecimal total;
    private String grade;
    private BigDecimal gradePoints;

    @Column(nullable = false)
    private BigDecimal credits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Remark remark;

    private String failReason;

    @Column(nullable = false)
    private boolean capped;
    @Column(nullable = false)
    private boolean locked;
    @Column(nullable = false)
    private OffsetDateTime computedAt = OffsetDateTime.now();
}
