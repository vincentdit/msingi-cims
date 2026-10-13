package tz.co.msingi.cims.finance.adjustments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class AdjustmentDtos {

    private AdjustmentDtos() {
    }

    public record AdjustmentRequest(@NotNull FinanceAdjustment.Kind kind, Long studentId, @Size(max = 40) String registrationNo, Long invoiceId,
                                    @NotNull @DecimalMin("1") BigDecimal amount, @NotBlank @Size(min = 10, max = 1000) String reason,
                                    @NotBlank @Size(max = 300) String evidenceRef) {
    }

    public record DecisionRequest(@Size(max = 1000) String note) {
    }

    public record PaidRequest(@NotBlank @Size(max = 100) String reference) {
    }

    public record SettingsView(BigDecimal approvalThreshold) {
    }

    public record AdjustmentView(Long id, String adjustmentNo, String kind, Long studentId, String registrationNo, String studentName,
                                 Long invoiceId, String invoiceNo, BigDecimal amount, String reason, String evidenceRef, String status,
                                 boolean needsSecondLevel, String requestedByName, OffsetDateTime requestedAt, String firstByName,
                                 OffsetDateTime firstAt, String secondByName, OffsetDateTime secondAt, String decisionNote, String documentNo,
                                 String paidReference, OffsetDateTime paidAt, String waitingFor, boolean canApprove, boolean canReject,
                                 boolean canMarkPaid) {
    }

    /** What can be waived or refunded for a student: open invoices and credit. */
    public record StudentOptions(Long studentId, String registrationNo, String name, BigDecimal credit, BigDecimal refundable,
                                 List<InvoiceOption> invoices) {
    }

    public record InvoiceOption(Long id, String invoiceNo, String description, BigDecimal balance, BigDecimal waivable) {
    }
}
