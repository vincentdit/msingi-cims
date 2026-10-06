package tz.co.msingi.cims.finance.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "invoices")
public class Invoice extends BaseEntity {

    public enum PayerType { APPLICANT, STUDENT, SPONSOR }

    public enum Status { ISSUED, PARTIALLY_PAID, PAID, CANCELLED, CREDITED }

    @Column(nullable = false)
    private String invoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayerType payerType;

    @Column(nullable = false)
    private Long payerId;

    @Column(nullable = false)
    private String payerName;

    private String payerPhone;
    private String payerEmail;

    @Column(nullable = false)
    private String purpose;

    private Long referenceId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, columnDefinition = "char(3)")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String currency = "TZS";

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ISSUED;

    private LocalDate dueDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST, orphanRemoval = false)
    @OrderBy("id")
    private List<InvoiceLine> lines = new ArrayList<>();

    public void addLine(String feeItemCode, String description, BigDecimal amount) {
        InvoiceLine line = new InvoiceLine();
        line.setInvoice(this);
        line.setFeeItemCode(feeItemCode);
        line.setDescription(description);
        line.setAmount(amount);
        lines.add(line);
        totalAmount = totalAmount.add(amount);
    }

    public BigDecimal balance() {
        return totalAmount.subtract(paidAmount);
    }

    /** Applies a payment and returns true if the invoice became fully paid. */
    public boolean applyPayment(BigDecimal amount) {
        paidAmount = paidAmount.add(amount);
        if (paidAmount.compareTo(totalAmount) >= 0) {
            status = Status.PAID;
            return true;
        }
        status = Status.PARTIALLY_PAID;
        return false;
    }
}
