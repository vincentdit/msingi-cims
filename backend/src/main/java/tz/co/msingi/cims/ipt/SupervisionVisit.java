package tz.co.msingi.cims.ipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A supervision visit (or call) by the institution supervisor. */
@Getter
@Setter
@Entity
@Table(name = "ipt_visits")
public class SupervisionVisit extends TenantScopedEntity {

    public enum Mode { VISIT, PHONE, ONLINE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "placement_id")
    private IptPlacement placement;

    @Column(nullable = false)
    private LocalDate visitedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode;

    @Column(nullable = false)
    private String notes;

    @Column(nullable = false)
    private String recordedByName;

    @Column(nullable = false)
    private OffsetDateTime recordedAt = OffsetDateTime.now();
}
