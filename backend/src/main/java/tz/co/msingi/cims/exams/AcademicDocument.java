package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** An issued statement of results / provisional transcript (FR-EXM-13): a frozen snapshot, verifiable by its code. */
@Getter
@Setter
@Entity
@Table(name = "academic_documents")
public class AcademicDocument extends BaseEntity {

    public enum Kind { TRANSCRIPT }

    public enum Status { VALID, SUPERSEDED, REVOKED }

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.TRANSCRIPT;

    @Column(nullable = false)
    private String documentNo;
    @Column(nullable = false)
    private String verificationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VALID;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private TranscriptContent content;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, columnDefinition = "char(64)")
    private String contentSha256;
    @Column(nullable = false)
    private OffsetDateTime issuedAt = OffsetDateTime.now();
    @Column(nullable = false)
    private String issuedByName;
}
