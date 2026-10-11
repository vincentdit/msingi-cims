package tz.co.msingi.cims.teaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** Permission to sit an examination despite not meeting the eligibility rules; the requester cannot decide it. */
@Getter
@Setter
@Entity
@Table(name = "eligibility_exemptions")
public class EligibilityExemption extends BaseEntity {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Column(name = "offering_id", nullable = false, updatable = false)
    private Long offeringId;

    @Column(name = "module_registration_id", nullable = false, updatable = false)
    private Long moduleRegistrationId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false, updatable = false)
    private Long requestedById;

    @Column(nullable = false, updatable = false)
    private String requestedByName;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    private String decidedByName;
    private OffsetDateTime decidedAt;
    private String decisionNote;
}
