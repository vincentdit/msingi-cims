package tz.co.msingi.cims.finance.collection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** One account a private or faith-based institution is paid into: a mobile money business number or a bank account. */
@Getter
@Setter
@Entity
@Table(name = "payment_channels")
public class PaymentChannel extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider.Kind kind;

    @Column(nullable = false)
    private String accountName;

    /** Mobile money business (pay-bill) number, which takes a reference, or bank account number. */
    @Column(nullable = false)
    private String accountNumber;

    private String branch;

    /** Extra instructions shown to payers, replacing nothing: they follow the standard steps. */
    private String instructions;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int sortOrder;
}
