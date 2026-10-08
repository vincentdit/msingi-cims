package tz.co.msingi.cims.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** An institution's own wording of an event message (FR-COM-03). */
@Getter
@Setter
@Entity
@Table(name = "message_templates")
public class MessageTemplate extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private String code;
    @Column(nullable = false)
    private String bodyEn;
    @Column(nullable = false)
    private String bodySw;
    private boolean enabled = true;
    @Column(nullable = false)
    private String updatedByName;
}
