package tz.co.msingi.cims.finance.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The Bursar's sign-off of a month's reconciliations (BP-06 step 7). */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_signoffs")
public class ReconciliationSignoff extends TenantScopedEntity {

    @Column(nullable = false)
    private LocalDate month;
    @Column(nullable = false)
    private int daysReconciled;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal totalCollected;
    private String note;
    @Column(nullable = false)
    private OffsetDateTime signedAt;
    @Column(nullable = false)
    private Long signedBy;
    @Column(nullable = false)
    private String signedByName;
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
