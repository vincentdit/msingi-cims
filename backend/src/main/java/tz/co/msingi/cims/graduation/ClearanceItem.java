package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.time.OffsetDateTime;

/** One office's decision on a clearance: pending, cleared or held with a reason. */
@Getter
@Setter
@Entity
@Table(name = "clearance_items")
public class ClearanceItem extends TenantScopedEntity {

    public enum Status { PENDING, CLEARED, HELD }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clearance_id")
    private Clearance clearance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private ClearanceOffice office;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private String note;
    private String decidedByName;
    private OffsetDateTime decidedAt;
}
