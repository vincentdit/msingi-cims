package tz.co.msingi.cims.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;
import java.util.Map;

/** Append-only audit record (the database rejects updates and deletes). */
@Getter
@Setter
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "audit_logs")
public class AuditLog extends TenantScopedEntity {

    @Column(nullable = false, updatable = false)
    private OffsetDateTime occurredAt = OffsetDateTime.now();

    private Long actorUserId;
    private String actorName;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String entity;

    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    private String ipAddress;
    private String correlationId;
}
