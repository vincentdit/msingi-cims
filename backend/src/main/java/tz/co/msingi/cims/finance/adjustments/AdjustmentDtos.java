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

    /** amount may be left out for a discount or scholarship with a scheme: it is worked out from the scheme. */
    public record AdjustmentRequest(@NotNull FinanceAdjustment.Kind kind, Long studentId, @Size(max = 40) String registrationNo, Long invoiceId,
                                    @DecimalMin("1") BigDecimal amount, @NotBlank @Size(min = 10, max = 1000) String reason,
                                    @NotBlank @Size(max = 300) String evidenceRef, Long schemeId) {
        public AdjustmentRequest(FinanceAdjustment.Kind kind, Long studentId, String registrationNo, Long invoiceId, BigDecimal amount,
                                 String reason, String evidenceRef) {
            this(kind, studentId, registrationNo, invoiceId, amount, reason, evidenceRef, null);
        }
    }

    public record SchemeRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z0-9_\\-]{2,20}") String code,
                                @NotBlank @Size(max = 150) String name, @NotNull FinanceAdjustment.Kind kind,
                                @NotBlank @jakarta.validation.constraints.Pattern(regexp = "PERCENT|FIXED") String basis,
                                @NotNull @DecimalMin("0.01") BigDecimal value, @Size(max = 20) String feeCategory,
                                @Size(max = 500) String description, boolean active) {
    }

    public record SchemeView(Long id, String code, String name, String kind, String basis, BigDecimal value, String feeCategory,
                             String description, boolean active) {
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
                                 boolean canMarkPaid, Long schemeId, String schemeName, String origin) {
    }

    /** What can be waived or refunded for a student: open invoices and credit. */
    public record StudentOptions(Long studentId, String registrationNo, String name, BigDecimal credit, BigDecimal refundable,
                                 List<InvoiceOption> invoices) {
    }

    /** An invoice of the student: what can be waived on it, and what paid on it can be refunded. */
    public record InvoiceOption(Long id, String invoiceNo, String description, BigDecimal balance, BigDecimal waivable, BigDecimal paid,
                                BigDecimal refundable) {
    }
}
