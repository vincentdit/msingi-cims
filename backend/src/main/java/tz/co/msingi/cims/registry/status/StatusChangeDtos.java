package tz.co.msingi.cims.registry.status;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class StatusChangeDtos {

    private StatusChangeDtos() {
    }

    public record StatusChangeRequest(@NotNull StatusChange.Type type, @NotBlank @Size(max = 1000) String reason,
                                      @Size(max = 120) String reference, @NotNull LocalDate effectiveDate, LocalDate untilDate) {
    }

    public record NoteRequest(@Size(max = 500) String note) {
    }

    public record DecisionRequest(boolean approve, @Size(max = 500) String note) {
    }

    public record StatusChangeView(Long id, Long studentId, String registrationNo, String studentName, String programmeCode,
                                   String departmentName, StatusChange.Type type, String fromStatus, String toStatus, String currentStatus,
                                   String reason, String reference, LocalDate effectiveDate, LocalDate untilDate, StatusChange.State state,
                                   String requestedByName, OffsetDateTime requestedAt, String recommendedByName,
                                   OffsetDateTime recommendedAt, String recommendationNote, String decidedByName, OffsetDateTime decidedAt,
                                   String decisionNote, OffsetDateTime appliedAt, boolean canRecommend, boolean canDecide, boolean canCancel) {
    }

    /** The changes Registry may request for a student in their current status. */
    public record StatusOptions(String currentStatus, java.util.List<StatusChange.Type> allowed, boolean openRequest) {
    }
}
