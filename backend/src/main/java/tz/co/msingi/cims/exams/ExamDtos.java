package tz.co.msingi.cims.exams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.academic.domain.Programme;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ExamDtos {

    private ExamDtos() {
    }

    // ------------------------------------------------------------------ regulations (FR-ACA-05)

    public record GradeBandDto(@NotBlank @Size(max = 3) String grade, @Min(0) @Max(100) short minMark, @Min(0) @Max(100) short maxMark,
                               @NotNull @DecimalMin("0") @DecimalMax("10") BigDecimal points, @Size(max = 40) String remark, boolean passing) {
    }

    public record ClassificationDto(@NotBlank @Size(max = 40) String name, @NotNull @DecimalMin("0") BigDecimal minGpa,
                                    @NotNull @DecimalMin("0") BigDecimal maxGpa) {
    }

    public record RegulationRequest(@NotBlank @Size(max = 120) String name, @NotNull Programme.AwardFamily awardFamily,
                                    @Min(1) @Max(8) short levelFrom, @Min(1) @Max(8) short levelTo, @NotNull Long effectiveYearId,
                                    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal caWeight,
                                    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal seWeight,
                                    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal caPassPercent,
                                    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal sePassPercent,
                                    @Min(0) @Max(3) short gpaDecimals, @NotNull RegulationSet.Rounding gpaRounding,
                                    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal suppMaxFailedPercent,
                                    @Size(max = 3) String suppMaxGrade, @DecimalMin("0") BigDecimal discontinueGpaBelow,
                                    @Size(max = 1000) String notes,
                                    @NotEmpty List<@Valid GradeBandDto> gradeBands, @NotNull List<@Valid ClassificationDto> classifications) {
    }

    public record RegulationView(Long id, String name, String awardFamily, short levelFrom, short levelTo, Long effectiveYearId,
                                 String effectiveYear, BigDecimal caWeight, BigDecimal seWeight, BigDecimal caPassPercent,
                                 BigDecimal sePassPercent, BigDecimal caPassMark, BigDecimal sePassMark, short gpaDecimals, String gpaRounding,
                                 BigDecimal suppMaxFailedPercent, String suppMaxGrade, BigDecimal discontinueGpaBelow, String notes,
                                 String createdByName, boolean inUse, List<GradeBandDto> gradeBands, List<ClassificationDto> classifications) {
    }

    /** Fields left out (null) keep their current value. */
    public record SettingsView(Boolean useDepartmentBoard, BigDecimal minAttendancePercent, Boolean eligibilityRequiresCa,
                               Boolean eligibilityRequiresClearance, Integer attendanceAlertAfterSessions) {
    }

    // ------------------------------------------------------------------ offerings, plans and marks (FR-EXM-01/02/03/05)

    public record StaffOption(Long id, String fullName, List<String> roles) {
    }

    public record MarksWindowRequest(LocalDate marksEntryOpens, LocalDate marksEntryCloses) {
    }

    public record SetupRequest(@NotNull Long semesterId) {
    }

    public record SetupResult(int created, List<String> skipped) {
    }

    public record StaffRequest(@NotNull List<Long> userIds) {
    }

    public record ComponentDto(Long id, @NotBlank @Size(max = 80) String name, @NotNull AssessmentComponent.Category category,
                               @NotNull @DecimalMin(value = "0.5") @DecimalMax("100") BigDecimal weight,
                               @NotNull @DecimalMin(value = "1") @DecimalMax("1000") BigDecimal maxScore) {
    }

    public record PlanRequest(@NotEmpty List<@Valid ComponentDto> components) {
    }

    public record ScoreEntry(@NotNull Long moduleRegistrationId, @NotNull Long componentId, BigDecimal score, boolean absent) {
    }

    public record ScoresRequest(@NotNull List<@Valid ScoreEntry> entries) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record OptionalNote(@Size(max = 1000) String note) {
    }

    public record UploadError(int row, String message) {
    }

    public record UploadResult(int updated, List<UploadError> errors) {
    }

    public record Person(Long id, String fullName) {
    }

    public record OfferingSummary(Long id, Long semesterId, String semesterName, Long moduleId, String code, String title, BigDecimal credits,
                                  String departmentName, String status, int students, int complete, List<Person> lecturers,
                                  String regulation, String returnedNote, boolean mine) {
    }

    public record ScoreCell(BigDecimal score, boolean absent) {
    }

    public record StudentRow(Long moduleRegistrationId, Long studentId, String registrationNo, String name, String programmeCode,
                             String category, Map<Long, ScoreCell> scores, BigDecimal caTotal, BigDecimal seTotal, BigDecimal total,
                             String grade, BigDecimal points, String remark, String failReason, Boolean examEligible) {
    }

    public record Stats(int students, int graded, int passed, BigDecimal passRate, BigDecimal mean, Map<String, Integer> distribution,
                        List<String> flags) {
    }

    public record Permissions(boolean editPlan, boolean enterCa, boolean enterSe, boolean submit, boolean hodApprove, boolean boardApprove,
                              boolean returnToLecturer, boolean assign) {
    }

    public record OfferingView(Long id, Long semesterId, String semesterName, String academicYear, Long moduleId, String code, String title,
                               BigDecimal credits, String departmentName, String status, List<Person> lecturers, Long regulationId,
                               String regulation, BigDecimal caWeight, BigDecimal seWeight, BigDecimal caPassMark, BigDecimal sePassMark,
                               List<GradeBandDto> gradeBands, LocalDate marksEntryOpens, LocalDate marksEntryCloses, String marksWindowNote,
                               boolean useDepartmentBoard, List<ComponentDto> components, List<StudentRow> rows, Stats stats,
                               String submittedByName, OffsetDateTime submittedAt, String hodByName, OffsetDateTime hodAt,
                               String boardByName, OffsetDateTime boardAt, String returnedNote, String returnedByName,
                               OffsetDateTime returnedAt, Permissions can) {
    }

    // ------------------------------------------------------------------ class result sheets (FR-EXM-06/07, BP-08 steps 6-9)

    public record ComputeRequest(@NotNull Long semesterId, @NotNull Long programmeId, @Min(1) @Max(8) short yearOfStudy, ResultSheet.Round round) {
        public ResultSheet.Round roundOrMain() {
            return round == null ? ResultSheet.Round.MAIN : round;
        }
    }

    public record DecisionRequest(@NotNull SemesterResult.Decision decision, @Size(max = 500) String reason) {
    }

    public record SheetSummary(Long id, Long semesterId, Long programmeId, String programmeCode, String programmeName, short yearOfStudy,
                               String round, int students, String status, List<String> modulesPending, OffsetDateTime computedAt,
                               OffsetDateTime publishedAt, Map<String, Long> decisions) {
    }

    public record SheetModule(Long moduleId, String code, String title, BigDecimal credits) {
    }

    public record SheetCell(String grade, BigDecimal total, String remark, String attempt) {
    }

    public record SheetRow(Long studentId, String registrationNo, String name, String sex, Map<String, SheetCell> cells,
                           BigDecimal creditsRegistered, BigDecimal creditsPassed, BigDecimal gpa, BigDecimal cgpa, int modulesFailed,
                           String proposedDecision, String decision, String decisionReason, String classification) {
    }

    public record SheetPermissions(boolean compute, boolean decide, boolean recommend, boolean confirm, boolean publish, boolean returnSheet) {
    }

    public record SheetView(Long id, Long semesterId, String semesterName, String academicYear, Long programmeId, String programmeCode,
                            String programmeName, short yearOfStudy, String round, String status, String regulation, List<SheetModule> modules,
                            List<SheetRow> rows, Map<String, Long> decisions, String computedByName, OffsetDateTime computedAt,
                            String recommendedByName, OffsetDateTime recommendedAt, String committeeNote, String confirmedByName,
                            OffsetDateTime confirmedAt, String publishedByName, OffsetDateTime publishedAt, String returnedNote,
                            String returnedByName, OffsetDateTime returnedAt, List<SubmissionView> nactvetSubmissions, SheetPermissions can) {
    }

    public record SubmissionView(Long id, String status, String reference, String note, int students, String submittedByName,
                                 OffsetDateTime submittedAt, String responseByName, OffsetDateTime responseAt) {
    }

    public record SubmissionRequest(@Size(max = 100) String reference, @Size(max = 1000) String note) {
    }

    public record SubmissionResponse(@NotNull NactvetResultSubmission.Status status, @Size(max = 1000) String note) {
    }

    // ------------------------------------------------------------------ student portal (FR-EXM-10)

    public record StudentModuleResult(String code, String title, BigDecimal credits, String grade, BigDecimal points, String remark, String attempt) {
    }

    public record StudentSemesterResult(Long id, String semesterName, String academicYear, short yearOfStudy, String round, OffsetDateTime publishedAt,
                                        boolean withheld, String withheldReason, List<StudentModuleResult> modules, BigDecimal creditsRegistered,
                                        BigDecimal creditsPassed, BigDecimal gpa, BigDecimal cgpa, String decision) {
    }

    public record StudentResults(List<StudentSemesterResult> semesters) {
    }

    // ------------------------------------------------------------------ supplementary and special examinations (FR-EXM-08, BP-10)

    public record SessionRequest(@NotNull @DecimalMin("0") BigDecimal supplementaryFee, @NotNull @DecimalMin("0") BigDecimal specialFee,
                                 @NotNull LocalDate registrationCloses, @Size(max = 120) String examPeriod) {
    }

    public record SessionModule(Long offeringId, String code, String title, long supplementary, long special, long awaitingPayment,
                                String resitStatus) {
    }

    public record SessionView(Long id, Long semesterId, String semesterName, String academicYear, BigDecimal supplementaryFee,
                              BigDecimal specialFee, LocalDate registrationCloses, String examPeriod, boolean registrationOpen,
                              long eligibleStudents, long eligibleModules, long registered, List<SessionModule> modules) {
    }

    public record EligibleModule(Long moduleResultId, String code, String title, BigDecimal credits, String kind, String firstGrade,
                                 String firstRemark, BigDecimal fee, Long resitId, String resitStatus, boolean paid, String grade, String remark) {
    }

    public record StudentResits(Long sessionId, String semesterName, String academicYear, LocalDate registrationCloses, String examPeriod,
                                boolean registrationOpen, List<EligibleModule> modules, Long invoiceId, String invoiceNo, String controlNo,
                                BigDecimal amountDue) {
    }

    public record ResitRegisterRequest(@NotNull Long sessionId, @NotEmpty List<Long> moduleResultIds) {
    }

    public record ResitRow(Long resitId, Long studentId, String registrationNo, String name, String programmeCode, String kind, boolean paid,
                           BigDecimal caTotal, String firstGrade, BigDecimal score, boolean absent, BigDecimal seTotal, BigDecimal total,
                           String grade, boolean capped, String remark, String failReason) {
    }

    public record ResitMarksView(Long offeringId, String code, String title, String status, String submittedByName, OffsetDateTime submittedAt,
                                 String approvedByName, OffsetDateTime approvedAt, String returnedNote, BigDecimal seWeight, String suppMaxGrade,
                                 List<ResitRow> rows, boolean canEnter, boolean canSubmit, boolean canApprove, boolean canReturn) {
    }

    public record ResitScore(@NotNull Long resitId, @DecimalMin("0") @DecimalMax("100") BigDecimal score, boolean absent) {
    }

    public record ResitScoresRequest(@NotNull List<@Valid ResitScore> entries) {
    }

    // ------------------------------------------------------------------ amendments to confirmed results

    public record AmendmentRequest(@NotNull Long moduleResultId, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal caTotal,
                                   @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal seTotal, @NotBlank @Size(max = 1000) String reason,
                                   @Size(max = 200) String evidenceRef) {
    }

    public record AmendmentView(Long id, Long moduleResultId, Long studentId, String registrationNo, String studentName, String moduleCode,
                                String moduleTitle, String semester, BigDecimal oldCaTotal, BigDecimal oldSeTotal, BigDecimal oldTotal,
                                String oldGrade, String oldRemark, BigDecimal newCaTotal, BigDecimal newSeTotal, BigDecimal newTotal,
                                String newGrade, String newRemark, String reason, String evidenceRef, String status, String requestedByName,
                                OffsetDateTime requestedAt, String decidedByName, OffsetDateTime decidedAt, String decisionNote, boolean canDecide) {
    }

    public record StudentModuleRecord(Long moduleResultId, String semester, String code, String title, BigDecimal caTotal, BigDecimal seTotal,
                                      BigDecimal total, String grade, String remark, boolean locked, boolean pendingAmendment,
                                      String resitKind, String resitGrade, String resitRemark) {
    }

    // ------------------------------------------------------------------ transcripts (FR-EXM-13)

    public record DocumentView(Long id, String kind, String documentNo, String verificationCode, String status, OffsetDateTime issuedAt,
                               String issuedByName, BigDecimal cgpa, int semesters) {
    }

    public record TranscriptVerification(String documentType, String status, String institution, String institutionNactvetNo, String letterNo,
                                         int edition, OffsetDateTime issuedAt, String holderName, String registrationNo, String programme,
                                         String award, String nactvetVerificationNo, BigDecimal cgpa, String classification,
                                         BigDecimal creditsEarned, String lastSemester) {
    }
}
