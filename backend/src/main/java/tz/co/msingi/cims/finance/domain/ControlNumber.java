package tz.co.msingi.cims.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** A GePG control number issued for an invoice. An invoice may receive a new one after expiry. */
@Getter
@Setter
@Entity
@Table(name = "control_numbers")
public class ControlNumber extends BaseEntity {

    public enum Status { REQUESTED, ACTIVE, PAID, EXPIRED, FAILED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    /** Bill id sent to GePG: "&lt;tenantId&gt;-&lt;invoiceId&gt;-&lt;n&gt;"; identifies the institution on callbacks. */
    @Column(nullable = false, unique = true)
    private String billId;

    @Column(unique = true)
    private String controlNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.REQUESTED;

    private OffsetDateTime issuedAt;
    private OffsetDateTime expiresAt;

    public boolean isUsable() {
        return status == Status.ACTIVE && (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now()));
    }
}
