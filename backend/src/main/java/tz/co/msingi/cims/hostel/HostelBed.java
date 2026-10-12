package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** A bed in a room; at most one active allocation (BRU-18, enforced by the database). */
@Getter
@Setter
@Entity
@Table(name = "hostel_beds")
public class HostelBed extends TenantScopedEntity {

    public enum Status { AVAILABLE, MAINTENANCE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private HostelRoom room;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;
}
