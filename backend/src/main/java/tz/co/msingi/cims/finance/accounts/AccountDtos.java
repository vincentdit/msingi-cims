package tz.co.msingi.cims.finance.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record LineView(String feeItemCode, String description, BigDecimal amount, BigDecimal paid, BigDecimal balance, int instalment) {
    }

    public record InvoiceView(Long id, String invoiceNo, String purpose, String description, String payerType, BigDecimal total,
                              BigDecimal paid, BigDecimal balance, String status, LocalDate dueDate, OffsetDateTime issuedAt,
                              String controlNo, OffsetDateTime controlNoExpiresAt, List<LineView> lines) {
    }

    public record PaymentView(String receiptNo, OffsetDateTime paidAt, BigDecimal amount, BigDecimal credit, String channel,
                              String pspName, String invoiceNo, String controlNo) {
    }

    /** {@code sponsorPending}: invoiced to sponsors for this student and not yet paid by them; {@code sponsors}: who sponsors the student. */
    public record Statement(String holderName, String registrationNo, BigDecimal billed, BigDecimal paid, BigDecimal balance,
                            BigDecimal credit, List<InvoiceView> invoices, List<PaymentView> payments, BigDecimal sponsorPending,
                            List<SponsorShare> sponsors) {
    }

    public record SponsorShare(String sponsor, String academicYear, BigDecimal coveragePercent, BigDecimal capAmount, String reference) {
    }

    public record Clearance(String gate, boolean enabled, boolean cleared, String academicYear, BigDecimal invoiceTotal,
                            BigDecimal paid, BigDecimal balance, BigDecimal minPercentPaid, BigDecimal paidPercent,
                            boolean firstInstalmentRequired, BigDecimal firstInstalmentBalance, BigDecimal amountNeeded,
                            Long invoiceId, String invoiceNo, String reason) {
    }
}
