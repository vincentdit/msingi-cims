package tz.co.msingi.cims.admission.letter;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** An issued admission letter (one edition). Reissuing creates a new edition and supersedes this one. */
@Getter
@Setter
@Entity
@Table(name = "admission_letters")
public class AdmissionLetter extends BaseEntity {

    public enum Status { VALID, SUPERSEDED, REVOKED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private Application application;

    @Column(nullable = false)
    private String letterNo;

    @Column(nullable = false)
    private short edition;

    @Column(nullable = false)
    private String verificationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALID;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private LetterContent content;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, columnDefinition = "char(64)")
    private String contentSha256;

    @Column(nullable = false)
    private OffsetDateTime issuedAt;

    private Long issuedBy;

    @Column(nullable = false)
    private String issuedByName;

    private String reissueReason;
    private OffsetDateTime revokedAt;
    private String revokedByName;
    private String revokeReason;
}
