package tz.co.msingi.cims.exams.appeal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AppealDtos {

    private AppealDtos() {
    }

    public record AppealRequest(@NotNull Long moduleResultId, @NotNull ResultAppeal.Kind kind, @NotBlank @Size(max = 1000) String reason) {
    }

    public record AssignRequest(@NotNull Long examinerUserId) {
    }

    /** The examiner's marks; for a remark only the examination (SE) is marked again and coursework (CA) stays. */
    public record MarkRequest(@DecimalMin("0") @DecimalMax("100") BigDecimal caTotal, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal seTotal,
                              @NotBlank @Size(max = 1000) String comment) {
    }

    public record NoteRequest(@Size(max = 1000) String note) {
    }

    /** A published module result a student may still ask to have re-marked or appeal. */
    public record EligibleResult(Long moduleResultId, String moduleCode, String moduleTitle, String semester, BigDecimal caTotal, BigDecimal seTotal,
                                 BigDecimal total, String grade, String remark, LocalDate lastDay) {
    }

    public record AppealView(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, Long moduleResultId,
                             String moduleCode, String moduleTitle, String semester, String kind, String reason, BigDecimal fee, boolean paid,
                             String invoiceNo, String controlNo, String status, String outcome, BigDecimal originalCaTotal,
                             BigDecimal originalSeTotal, BigDecimal originalTotal, String originalGrade, String examinerName,
                             String assignedByName, OffsetDateTime assignedAt, BigDecimal newCaTotal, BigDecimal newSeTotal, BigDecimal newTotal,
                             String newGrade, String examinerComment, OffsetDateTime markedAt, Long amendmentId, String decisionNote,
                             String decidedByName, OffsetDateTime closedAt, OffsetDateTime requestedAt, boolean canAssign, boolean canMark,
                             boolean canForward, boolean canReject, boolean canWithdraw, BigDecimal caWeight, BigDecimal seWeight) {
    }

    public record StudentAppeals(int windowDays, BigDecimal fee, List<EligibleResult> eligible, List<AppealView> appeals) {
    }

    public record ExaminerOption(Long id, String name, List<String> roles) {
    }
}
