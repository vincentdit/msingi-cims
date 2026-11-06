package tz.co.msingi.cims.organisation;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** Whether the institution shows its organisation structure on its public site. */
@Getter
@Setter
@Entity
@Table(name = "org_settings")
public class OrgSettings extends BaseEntity {

    private OffsetDateTime publishedAt;
    private String publishedBy;
}
