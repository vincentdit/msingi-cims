package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** A lecturer or assessor allocated to a module offering. */
@Getter
@Setter
@Entity
@Table(name = "offering_staff")
public class OfferingStaff extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id")
    private ModuleOffering offering;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
