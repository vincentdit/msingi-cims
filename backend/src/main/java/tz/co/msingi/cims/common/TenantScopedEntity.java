package tz.co.msingi.cims.common;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

/**
 * Lightweight tenant-owned entity for child/append-only tables that have no
 * audit timestamps or optimistic locking (e.g. invoice lines, subject grades).
 */
@Getter
@MappedSuperclass
public abstract class TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @PrePersist
    protected void stampTenant() {
        if (tenantId == null) {
            tenantId = TenantContext.requireTenantId();
        }
    }
}
