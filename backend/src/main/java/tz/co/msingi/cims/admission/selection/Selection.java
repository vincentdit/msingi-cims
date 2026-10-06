package tz.co.msingi.cims.admission.selection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One application's outcome in a selection round. */
@Getter
@Setter
@Entity
@Table(name = "selections")
public class Selection extends TenantScopedEntity {

    public enum Status { SELECTED, NOT_SELECTED, EXCLUDED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id")
    private SelectionRound round;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "window_programme_id")
    private WindowProgramme windowProgramme;

    private Short choiceOrder;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal meritScore;

    private Integer rankInProgramme;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String exclusionReason;
    private String excludedByName;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        stampTenant();
        createdAt = OffsetDateTime.now();
    }
}
