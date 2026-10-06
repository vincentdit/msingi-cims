package tz.co.msingi.cims.platform.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;

/** One-time code sent by SMS (phone verification, password reset). Only a hash is stored. */
@Getter
@Setter
@Entity
@Table(name = "otp_codes")
public class OtpCode extends TenantScopedEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    private OffsetDateTime consumedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
