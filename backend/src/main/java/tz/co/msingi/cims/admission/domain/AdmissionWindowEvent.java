package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;

/** One change to when an admission window takes applications: published, opened, closed, reopened, extended or rescheduled. */
@Getter
@Setter
@Entity
@Table(name = "admission_window_events")
public class AdmissionWindowEvent extends TenantScopedEntity {

    public enum Action { CREATED, PUBLISHED, OPENED, CLOSED, REOPENED, EXTENDED, RESCHEDULED }

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    private String reason;
    private OffsetDateTime opensAt;
    private OffsetDateTime closesAt;
    private OffsetDateTime previousClosesAt;

    @Column(nullable = false)
    private int applicantsNotified;

    @Column(nullable = false)
    private boolean automatic;

    @Column(nullable = false)
    private String byName;

    @Column(nullable = false)
    private OffsetDateTime at = OffsetDateTime.now();
}
