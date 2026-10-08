package tz.co.msingi.cims.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.io.Serializable;
import java.time.OffsetDateTime;

/** A student has opened a notice. */
@Getter
@Setter
@Entity
@Table(name = "notice_reads")
public class NoticeRead {

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "notice_id")
        private Long noticeId;
        @Column(name = "user_id")
        private Long userId;
    }

    @EmbeddedId
    private Key id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(nullable = false)
    private OffsetDateTime readAt;

    @PrePersist
    void onCreate() {
        if (tenantId == null) tenantId = TenantContext.requireTenantId();
        if (readAt == null) readAt = OffsetDateTime.now();
    }
}
