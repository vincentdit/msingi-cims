package tz.co.msingi.cims.platform.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/** An institution registered on the platform. Not tenant-scoped itself. */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String shortName;
    private String nactvetRegNo;

    /** Registration number pattern (FR-REG-02); null means the default pattern. */
    private String registrationNoPattern;

    @Column(nullable = false)
    private String ownership = "PRIVATE";

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** Logo URL, colours, tagline, contact text for the branded portals. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> branding = new HashMap<>();

    private String contactEmail;
    private String contactPhone;

    /** Why the institution was last suspended or reactivated, when and by whom (platform console). */
    private String statusReason;
    private OffsetDateTime statusChangedAt;
    private String statusChangedBy;
    private String onboardedBy;
    /** Msingi's own notes on the institution (contract, contacts, support history); never shown to the institution. */
    private String notes;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public TenantContext.TenantInfo toInfo() {
        return new TenantContext.TenantInfo(id, code);
    }
}
