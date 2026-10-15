package tz.co.msingi.cims.notification.email;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** An email in the outbox (FR-COM-01). Attachments are kept only until the email is sent. */
@Getter
@Setter
@Entity
@Table(name = "email_messages")
public class EmailMessage extends BaseEntity {

    public enum Kind { EVENT, SECURITY, LETTER, REPORT }

    public enum Status { QUEUED, SENT, FAILED }

    @Column(nullable = false)
    private String toAddress;
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String templateCode;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    private String attachmentName;
    private String attachmentType;

    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "bytea")
    private byte[] attachment;

    private Integer attachmentSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(nullable = false)
    private short attempts;

    private String providerRef;
    private String error;

    @Column(nullable = false)
    private OffsetDateTime queuedAt = OffsetDateTime.now();
    private OffsetDateTime sentAt;
}
