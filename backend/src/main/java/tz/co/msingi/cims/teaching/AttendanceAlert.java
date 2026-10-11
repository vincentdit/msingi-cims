package tz.co.msingi.cims.teaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A student warned that their attendance in a module is below the examination threshold. */
@Getter
@Setter
@Entity
@Table(name = "attendance_alerts")
public class AttendanceAlert extends TenantScopedEntity {

    @Column(name = "offering_id", nullable = false)
    private Long offeringId;

    @Column(name = "module_registration_id", nullable = false)
    private Long moduleRegistrationId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal percent;

    @Column(nullable = false)
    private OffsetDateTime alertedAt = OffsetDateTime.now();
}
