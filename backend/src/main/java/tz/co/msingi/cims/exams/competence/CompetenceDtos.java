package tz.co.msingi.cims.exams.competence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CompetenceDtos {

    private CompetenceDtos() {
    }

    public record UnitSummary(Long id, Long semesterId, String code, String title, String departmentName, String status, int students,
                              int tasks, List<String> assessors, int competent, int notYetCompetent, int incomplete, boolean mine,
                              String returnedNote) {
    }

    public record SetupResult(int created, List<String> skipped) {
    }

    public record TaskDto(Long id, @NotBlank @Size(max = 150) String name, @NotNull CompetenceTask.Kind kind,
                          @Size(max = 300) String evidenceRequired, Boolean required) {
    }

    public record TasksRequest(@NotEmpty @Size(max = 20) List<@Valid TaskDto> tasks) {
    }

    public record AssessorsRequest(@NotNull List<Long> userIds) {
    }

    public record Entry(@NotNull Long moduleRegistrationId, @NotNull Long taskId, @NotNull CompetenceAssessment.Outcome outcome,
                        @DecimalMin("0") @DecimalMax("100") BigDecimal practicalScore, @DecimalMin("0") @DecimalMax("100") BigDecimal theoryScore,
                        @NotNull LocalDate assessedOn, @NotBlank @Size(max = 300) String evidenceRef, @Size(max = 500) String comment,
                        Boolean reassess) {
    }

    public record EntriesRequest(@NotEmpty @Size(max = 500) List<@Valid Entry> entries) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    /** The latest attempt at one task, with the earlier ones. */
    public record Cell(Long id, int attempt, String outcome, BigDecimal practicalScore, BigDecimal theoryScore, LocalDate assessedOn,
                       String evidenceRef, String comment, String assessorName, boolean verified, int attemptsLeft, List<Cell> earlier) {
    }

    public record Row(Long moduleRegistrationId, Long studentId, String registrationNo, String name, String programmeCode,
                      Map<Long, Cell> tasks, String outcome) {
    }

    public record Permissions(boolean editTasks, boolean assign, boolean record, boolean submit, boolean verify, boolean returnUnit,
                              boolean reopen) {
    }

    public record TaskView(Long id, String name, String kind, String evidenceRequired, boolean required) {
    }

    public record Person(Long id, String fullName) {
    }

    public record UnitView(Long id, Long semesterId, String semesterName, String academicYear, String code, String title,
                           String departmentName, String status, int maxAttempts, List<TaskView> tasks, List<Person> assessors,
                           List<Row> rows, int competent, int notYetCompetent, int incomplete, String returnedNote, String returnedByName,
                           String submittedByName, OffsetDateTime submittedAt, String verifiedByName, OffsetDateTime verifiedAt,
                           Permissions can) {
    }

    // ------------------------------------------------------------------ award eligibility (BP-09 step 4)

    public record AwardClass(Long programmeId, String programmeCode, String programmeName, int awardLevel, int yearOfStudy, int students,
                             int unitsVerified, int unitsTotal, Long listId, OffsetDateTime confirmedAt, String confirmedByName,
                             Integer eligible) {
    }

    public record AwardLine(Long studentId, String registrationNo, String name, String sex, int unitsCompetent, int unitsRequired,
                            boolean eligible, List<String> outstanding) {
    }

    public record AwardList(Long semesterId, String semesterName, Long programmeId, String programmeCode, String programmeName,
                            String award, int yearOfStudy, List<AwardLine> lines, int eligible, Long listId, OffsetDateTime confirmedAt,
                            String confirmedByName, List<String> unitsNotVerified, boolean canConfirm) {
    }

    public record ConfirmRequest(@NotNull Long semesterId, @NotNull Long programmeId, @NotNull Short yearOfStudy) {
    }

    // ------------------------------------------------------------------ student portal

    public record MyUnit(String code, String title, String semesterName, String outcome, List<MyTask> tasks) {
    }

    public record MyTask(String name, String kind, String outcome, int attempt, LocalDate assessedOn) {
    }
}
