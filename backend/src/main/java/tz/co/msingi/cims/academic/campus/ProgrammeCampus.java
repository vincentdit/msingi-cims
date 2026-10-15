package tz.co.msingi.cims.academic.campus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

/** A campus a programme is taught at. A programme with none is taught at the main campus. */
@Getter
@Setter
@Entity
@Table(name = "programme_campuses")
public class ProgrammeCampus extends TenantScopedEntity {
    @Column(nullable = false)
    private Long programmeId;
    @Column(nullable = false)
    private Long campusId;
}
