package tz.co.msingi.cims.exams;

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

import java.time.OffsetDateTime;

/** A results file sent to NACTVET for a result sheet, and NACTVET's response (FR-EXM-11). */
@Getter
@Setter
@Entity
@Table(name = "nactvet_result_submissions")
public class NactvetResultSubmission extends TenantScopedEntity {

    public enum Status { SUBMITTED, ACCEPTED, QUERIED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_sheet_id")
    private ResultSheet sheet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SUBMITTED;

    private String reference;
    private String note;
    @Column(nullable = false)
    private int students;
    @Column(nullable = false)
    private String submittedByName;
    @Column(nullable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();
    private String responseByName;
    private OffsetDateTime responseAt;

    /** FILE: the results file was sent outside CIMS; API: sent to NACTVET's results service, NACTVET's batch in externalRef. */
    @Column(nullable = false)
    private String channel = "FILE";
    private String externalRef;
    /** NACTVET's queries on individual students, as JSON [{registrationNo, reason}]. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String queries;
}
