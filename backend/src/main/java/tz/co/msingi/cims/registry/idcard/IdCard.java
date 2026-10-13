package tz.co.msingi.cims.registry.idcard;

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
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.registry.Student;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A student ID card (FR-REG-06). A student has at most one VALID card; reissuing replaces it, ending studies revokes it. */
@Getter
@Setter
@Entity
@Table(name = "student_id_cards")
public class IdCard extends BaseEntity {

    public enum Status { VALID, REPLACED, REVOKED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(nullable = false)
    private String cardNo;

    @Column(nullable = false, unique = true)
    private String verificationCode;

    @Column(nullable = false)
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALID;

    @Column(nullable = false)
    private String issuedByName;

    @Column(nullable = false)
    private OffsetDateTime issuedAt = OffsetDateTime.now();

    private String endedReason;
    private OffsetDateTime endedAt;
}
