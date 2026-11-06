package tz.co.msingi.cims.organisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A position in a unit (e.g. Principal, Head of Department) and who holds it: a staff account, a name, or nobody (vacant). */
@Getter
@Setter
@Entity
@Table(name = "org_positions")
public class OrgPosition extends BaseEntity {

    @Column(nullable = false)
    private Long unitId;
    @Column(nullable = false)
    private String title;
    /** The head of the unit: shown first, in the unit's box. */
    private boolean head;
    private Long holderUserId;
    private String holderName;
    private boolean acting;
    private int sortOrder;
}
