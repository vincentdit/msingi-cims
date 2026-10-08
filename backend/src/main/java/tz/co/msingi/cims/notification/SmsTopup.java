package tz.co.msingi.cims.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A prepaid SMS bundle purchase. */
@Getter
@Setter
@Entity
@Table(name = "sms_topups")
public class SmsTopup extends TenantScopedEntity {
    @Column(nullable = false)
    private int units;
    @Column(nullable = false)
    private BigDecimal amountPaid;
    @Column(nullable = false)
    private String reference;
    @Column(nullable = false)
    private OffsetDateTime recordedAt;
    @Column(nullable = false)
    private String recordedByName;
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
