package tz.co.msingi.cims.ipt;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class IptDtos {

    private IptDtos() {
    }

    // ------------------------------------------------------------------ organisations (FR-IPT-01)

    public record OrganisationRequest(@NotBlank @Size(max = 200) String name, @Size(max = 100) String sector, @Size(max = 80) String region,
                                      @Size(max = 80) String district, @Size(max = 300) String address, @Size(max = 150) String contactName,
                                      @Size(max = 120) String contactTitle, @Size(max = 20) String contactPhone, @Email @Size(max = 150) String contactEmail,
                                      @Min(0) Integer capacity, Boolean active) {
    }

    public record OrganisationView(Long id, String name, String sector, String region, String district, String address, String contactName,
                                   String contactTitle, String contactPhone, String contactEmail, Integer capacity, boolean active,
                                   boolean proposedByStudent, int placements) {
    }

    // ------------------------------------------------------------------ periods

    public record PeriodRequest(@NotBlank @Size(max = 120) String name, @NotNull Long semesterId, Long moduleId, @NotNull LocalDate startsOn,
                                @NotNull LocalDate endsOn, LocalDate requestsClose, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal workplaceWeight,
                                @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal institutionWeight,
                                @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal logbookWeight, @Min(0) @Max(60) Integer minLogbookWeeks) {
    }

    public record PeriodView(Long id, String name, Long semesterId, String semester, Long moduleId, String moduleCode, String moduleTitle,
                             LocalDate startsOn, LocalDate endsOn, LocalDate requestsClose, BigDecimal workplaceWeight, BigDecimal institutionWeight,
                             BigDecimal logbookWeight, int minLogbookWeeks, int eligible, int requested, int confirmed, int completed,
                             OffsetDateTime resultsTransferredAt, String resultsTransferredBy, boolean requestsOpen) {
    }

    public record EligibleStudent(Long studentId, String registrationNo, String name, String programmeCode, int yearOfStudy, String phone,
                                  Long placementId, String placementStatus) {
    }

    // ------------------------------------------------------------------ placements (FR-IPT-02)

    public record PlacementRequest(@NotNull Long periodId, @NotNull Long studentId, @NotNull Long organisationId, LocalDate startsOn, LocalDate endsOn,
                                   @Size(max = 150) String unit, @Size(max = 150) String workplaceSupervisorName,
                                   @Size(max = 120) String workplaceSupervisorTitle, @Size(max = 20) String workplaceSupervisorPhone,
                                   @Email @Size(max = 150) String workplaceSupervisorEmail, Long institutionSupervisorId) {
    }

    public record PlacementUpdate(@NotNull Long organisationId, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn, @Size(max = 150) String unit,
                                  @Size(max = 150) String workplaceSupervisorName, @Size(max = 120) String workplaceSupervisorTitle,
                                  @Size(max = 20) String workplaceSupervisorPhone, @Email @Size(max = 150) String workplaceSupervisorEmail,
                                  Long institutionSupervisorId) {
    }

    public record DecisionRequest(@NotNull Boolean confirm, @Size(max = 500) String note, Long institutionSupervisorId) {
    }

    public record ScoreView(String kind, BigDecimal score, String comments, String assessedByName, OffsetDateTime assessedAt) {
    }

    public record LogbookView(Long id, int weekNo, LocalDate weekStarting, String activities, String skills, String challenges, BigDecimal hours,
                              String status, OffsetDateTime submittedAt, String reviewerComment, String reviewedByName, OffsetDateTime reviewedAt) {
    }

    public record VisitView(Long id, LocalDate visitedOn, String mode, String notes, String recordedByName, OffsetDateTime recordedAt) {
    }

    public record PlacementSummary(Long id, Long periodId, Long studentId, String registrationNo, String studentName, String programmeCode,
                                   Long organisationId, String organisation, String region, String status, LocalDate startsOn, LocalDate endsOn,
                                   String workplaceSupervisorName, Long institutionSupervisorId, String institutionSupervisorName, int logbookWeeks,
                                   int logbookApproved, int logbookPending, int scores, BigDecimal totalMark, OffsetDateTime transferredAt,
                                   String letterNo) {
    }

    public record PlacementView(PlacementSummary summary, String unit, String workplaceSupervisorTitle, String workplaceSupervisorPhone,
                                String workplaceSupervisorEmail, String studentNote, String decisionNote, OffsetDateTime requestedAt,
                                String confirmedByName, OffsetDateTime confirmedAt, OffsetDateTime letterIssuedAt, String organisationContact,
                                String organisationPhone, String organisationAddress, List<LogbookView> logbook, List<VisitView> visits,
                                List<ScoreView> assessments, BigDecimal workplaceWeight, BigDecimal institutionWeight, BigDecimal logbookWeight,
                                Permissions can) {
    }

    public record Permissions(boolean manage, boolean supervise, boolean writeLogbook) {
    }

    public record ReviewRequest(boolean approve, @Size(max = 1000) String comment) {
    }

    public record VisitRequest(@NotNull LocalDate visitedOn, @NotNull SupervisionVisit.Mode mode, @NotBlank @Size(max = 2000) String notes) {
    }

    public record AssessmentRequest(@NotNull IptAssessment.Kind kind, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score,
                                    @Size(max = 2000) String comments) {
    }

    public record TransferResult(int transferred, List<String> notReady, String moduleCode) {
    }

    public record StaffOption(Long id, String fullName, List<String> roles) {
    }

    // ------------------------------------------------------------------ student portal (FR-STU-04)

    public record StudentRequest(@NotNull Long periodId, Long organisationId, @Size(max = 200) String newOrganisationName,
                                 @Size(max = 80) String newOrganisationRegion, @Size(max = 150) String newOrganisationContact,
                                 @Size(max = 20) String newOrganisationPhone, LocalDate startsOn, LocalDate endsOn, @Size(max = 150) String unit,
                                 @Size(max = 150) String workplaceSupervisorName, @Size(max = 20) String workplaceSupervisorPhone,
                                 @Size(max = 500) String note) {
    }

    public record LogbookRequest(@NotNull @Min(1) @Max(60) Integer weekNo, @NotNull LocalDate weekStarting, @NotBlank @Size(max = 4000) String activities,
                                 @Size(max = 2000) String skills, @Size(max = 2000) String challenges,
                                 @DecimalMin("0") @DecimalMax("100") BigDecimal hours, boolean submit) {
    }

    public record OrganisationOption(Long id, String name, String region) {
    }

    public record MyIpt(List<PeriodView> periods, List<OrganisationOption> organisations, PlacementView placement, List<PlacementSummary> history) {
    }
}
