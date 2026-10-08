package tz.co.msingi.cims.portal;

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
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** A student's request to correct personal details, applied only when Registry approves (FR-REG-08). */
@Getter
@Setter
@Entity
@Table(name = "student_change_requests")
public class StudentChangeRequest extends tz.co.msingi.cims.common.BaseEntity {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    public record Change(String from, String to) {
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Change> changes = new LinkedHashMap<>();

    @Column(nullable = false)
    private String reason;
    private boolean needsDocuments;

    @Column(nullable = false)
    private OffsetDateTime submittedAt;
    private OffsetDateTime decidedAt;
    private String decidedByName;
    private String decisionNote;
}
