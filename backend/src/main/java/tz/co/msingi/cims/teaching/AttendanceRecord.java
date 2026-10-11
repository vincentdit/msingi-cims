package tz.co.msingi.cims.teaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** One student's attendance at a class session. Late counts as attended; excused is left out of the percentage. */
@Getter
@Setter
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord extends TenantScopedEntity {

    public enum Status { PRESENT, LATE, ABSENT, EXCUSED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ClassSession session;

    @Column(name = "module_registration_id", nullable = false)
    private Long moduleRegistrationId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String note;
}
