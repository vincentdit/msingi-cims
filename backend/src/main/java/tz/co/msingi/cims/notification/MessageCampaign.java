package tz.co.msingi.cims.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/** A bulk message to a filtered group (FR-COM-02). */
@Getter
@Setter
@Entity
@Table(name = "message_campaigns")
public class MessageCampaign extends TenantScopedEntity {
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String body;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> audience;
    @Column(nullable = false)
    private String audienceLabel;
    @Column(nullable = false)
    private int recipientCount;
    @Column(nullable = false)
    private int smsParts;
    @Column(nullable = false)
    private BigDecimal estimatedCost;
    @Column(nullable = false)
    private OffsetDateTime sentAt;
    @Column(nullable = false)
    private Long sentBy;
    @Column(nullable = false)
    private String sentByName;
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
