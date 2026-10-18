package tz.co.msingi.cims.console.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** What the Msingi billing screens and documents show. */
public final class BillingModels {

    private BillingModels() {
    }

    /** An account institutions pay Msingi into: a bank account or a mobile money business number. */
    public record Channel(String provider, String accountName, String accountNumber, String branch) {
    }

    public record Settings(String companyName, String tin, String vrn, String address, String phone, String email, String website,
                           List<Channel> channels, String invoiceNote, String updatedByName, OffsetDateTime updatedAt,
                           boolean customLogo) {
    }

    public record ContractInput(String reference, LocalDate startDate, LocalDate endDate, BigDecimal setupFee, BigDecimal annualFee,
                                BigDecimal studentFee, BigDecimal applicationFee, BigDecimal applicationPercent, String usageCycle,
                                BigDecimal vatPercent, Integer paymentTermsDays, Boolean autoIssue, Boolean autoSuspend, Integer suspendAfterDays,
                                String billingContactName, String billingEmail, String billingPhone, String notes) {
    }

    public record Contract(Long id, Long tenantId, String reference, String status, LocalDate startDate, LocalDate endDate, String currency,
                           BigDecimal setupFee, BigDecimal annualFee, BigDecimal studentFee, BigDecimal applicationFee, BigDecimal applicationPercent,
                           String usageCycle, BigDecimal vatPercent, int paymentTermsDays, boolean autoIssue, boolean autoSuspend, int suspendAfterDays,
                           String billingContactName, String billingEmail, String billingPhone, String notes, boolean setupBilled,
                           LocalDate licenceBilledUntil, LocalDate usageBilledUntil, String createdByName, OffsetDateTime createdAt) {
    }

    /** What an institution used in a period, as billed: active students, applications with the fee paid, application fees collected. */
    public record Usage(LocalDate from, LocalDate to, long activeStudents, long paidApplications, BigDecimal applicationFeesCollected) {
    }

    public record Line(String kind, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceRow(Long id, Long tenantId, String institutionCode, String institutionName, String invoiceNo, String kind,
                             LocalDate periodFrom, LocalDate periodTo, String status, String currency, BigDecimal total, BigDecimal paidAmount,
                             BigDecimal balance, LocalDate issueDate, LocalDate dueDate, Integer daysOverdue, String paymentReference,
                             boolean generated, OffsetDateTime createdAt) {
    }

    public record Applied(Long paymentId, String receiptNo, OffsetDateTime paidAt, String method, BigDecimal amount) {
    }

    public record Reminder(int level, String kind, String sentTo, OffsetDateTime createdAt) {
    }

    public record InvoiceView(InvoiceRow invoice, List<Line> lines, BigDecimal subtotal, BigDecimal vatPercent, BigDecimal vatAmount, String notes,
                              List<Applied> payments, List<Reminder> reminders, String cancelledReason, String createdByName, String issuedByName) {
    }

    public record InvoiceInput(List<Line> lines, String notes, BigDecimal vatPercent) {
    }

    public record PaymentInput(Long tenantId, BigDecimal amount, LocalDate paidOn, String method, String provider, String reference,
                               Long invoiceId, String note) {
    }

    public record PaymentRow(Long id, Long tenantId, String institutionCode, String institutionName, String receiptNo, BigDecimal amount,
                             BigDecimal unallocated, OffsetDateTime paidAt, String method, String provider, String reference, String source,
                             String status, String recordedByName, String invoices, String reversedReason) {
    }

    public record Aging(BigDecimal current, BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90) {
    }

    public record Entry(LocalDate date, String kind, String reference, String description, BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    public record Statement(Long tenantId, String institutionCode, String institutionName, Contract contract, List<Entry> entries,
                            BigDecimal invoiced, BigDecimal paid, BigDecimal balance, BigDecimal credit, Aging aging) {
    }

    public record InstitutionSummary(Long tenantId, String code, String name, String tenantStatus, String contractStatus, BigDecimal annualFee,
                                     BigDecimal outstanding, BigDecimal overdue, Integer oldestOverdueDays, BigDecimal credit,
                                     OffsetDateTime lastPaymentAt, long drafts) {
    }

    public record Overview(int year, BigDecimal invoiced, BigDecimal collected, BigDecimal outstanding, BigDecimal overdue, long drafts,
                           long contracts, Aging aging, List<InstitutionSummary> institutions) {
    }

    /** What an institution's administrators see about their account with Msingi. */
    public record Account(Contract contract, BigDecimal balance, BigDecimal overdue, BigDecimal credit, List<InvoiceRow> invoices,
                          List<PaymentRow> payments, Settings payTo, boolean canSimulate) {
    }
}
