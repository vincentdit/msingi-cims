package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.admission.eligibility.EligibilityResult;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "application_choices")
public class ApplicationChoice extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_programme_id")
    private WindowProgramme windowProgramme;

    @Column(nullable = false)
    private short choiceOrder;

    @Column(nullable = false)
    private boolean eligible;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private EligibilityResult eligibilityDetail;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
