package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** An officer who decides an office's clearances. */
@Getter
@Setter
@Entity
@Table(name = "clearance_office_staff")
public class ClearanceOfficeStaff extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private ClearanceOffice office;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
