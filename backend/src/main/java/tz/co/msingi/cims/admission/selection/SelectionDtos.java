package tz.co.msingi.cims.admission.selection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class SelectionDtos {

    private SelectionDtos() {
    }

    public record ExcludeRequest(@NotNull Long applicationId, @NotBlank @Size(max = 300) String reason) {
    }

    public record ApproveRequest(@NotBlank @Size(max = 100) String committeeMinuteRef, @Size(max = 2000) String notes) {
    }

    public record RoundSummary(Long id, int roundNo, String status, OffsetDateTime proposedAt, String proposedByName,
                               OffsetDateTime approvedAt, String approvedByName, String committeeMinuteRef,
                               long selected, long notSelected, long excluded) {
    }

    public record Row(Long applicationId, String referenceNo, String applicantName, String phone, String sex, String region,
                      BigDecimal meritScore, Integer choiceOrder, Integer rank, Long windowProgrammeId, String programmeName,
                      String exclusionReason, String excludedByName) {
    }

    public record ProgrammeResult(Long windowProgrammeId, String programmeCode, String programmeName, int capacity,
                                  long filledInEarlierRounds, long availableThisRound, long applicants, long selectedCount,
                                  Map<String, Long> selectedBySex, List<Row> selected) {
    }

    public record Totals(long candidates, long selected, long notSelected, long excluded) {
    }

    public record RoundDetail(RoundSummary round, String meritFormula, Totals totals, List<ProgrammeResult> programmes,
                              List<Row> notSelected, List<Row> excluded, Map<String, Long> selectedBySex,
                              Map<String, Long> selectedByRegion, boolean canApprove) {
    }
}
