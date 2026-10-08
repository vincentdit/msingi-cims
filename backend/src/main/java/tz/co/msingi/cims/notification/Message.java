package tz.co.msingi.cims.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** One outbound SMS: queued in the transaction that caused it, sent after commit, updated by delivery reports. */
@Getter
@Setter
@Entity
@Table(name = "messages")
public class Message extends BaseEntity {

    public enum Kind { EVENT, BULK, SECURITY }

    public enum Status { QUEUED, SENT, DELIVERED, FAILED }

    @Column(nullable = false)
    private String channel = "SMS";
    @Column(nullable = false)
    private String phone;
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;
    private String templateCode;
    private Long campaignId;
    @Column(nullable = false)
    private String language;
    @Column(nullable = false)
    private String body;
    @Column(nullable = false)
    private short parts;
    @Column(nullable = false)
    private String encoding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;
    @Column(nullable = false)
    private short attempts;
    @Column(nullable = false)
    private int chargedParts;
    private String providerRef;
    private String error;
    @Column(nullable = false)
    private OffsetDateTime queuedAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime deliveredAt;
}
