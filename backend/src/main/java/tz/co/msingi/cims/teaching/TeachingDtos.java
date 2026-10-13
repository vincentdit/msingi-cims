package tz.co.msingi.cims.teaching;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TeachingDtos {

    private TeachingDtos() {
    }

    // ------------------------------------------------------------------ sessions and attendance (FR-TCH-02/03/04)

    public record Mark(@NotNull Long moduleRegistrationId, @NotNull AttendanceRecord.Status status, @Size(max = 200) String note) {
    }

    public record SessionRequest(UUID clientRef, @NotNull LocalDate sessionDate, @NotNull LocalTime startTime,
                                 @NotNull @Min(15) @Max(600) Integer durationMinutes, @NotNull ClassSession.Kind kind,
                                 @Size(max = 200) String topic, @NotNull @Valid List<Mark> marks) {
    }

    public record SessionSummary(Long id, LocalDate sessionDate, LocalTime startTime, int durationMinutes, String kind, String topic,
                                 int present, int late, int absent, int excused, String recordedByName, OffsetDateTime recordedAt,
                                 int selfCheckIns, boolean checkInOpen) {
    }

    /**
     * A line of a session's register. {@code source} is SELF when the student checked in themselves; {@code sharedDevice} flags a
     * check-in made from a phone that also checked in another student for the same session.
     */
    public record RegisterLine(Long moduleRegistrationId, Long studentId, String registrationNo, String name, String programmeCode,
                               String status, String note, String source, OffsetDateTime checkedInAt, boolean sharedDevice) {
    }

    /** Self check-in of a session: open from {@code openedAt} until {@code closesAt}; checking in after {@code lateAfterMinutes} counts as late. */
    public record CheckInState(boolean open, OffsetDateTime openedAt, OffsetDateTime closesAt, int lateAfterMinutes, OffsetDateTime closedAt,
                               int checkedIn, int classSize, int sharedDevices) {
    }

    public record SessionView(Long id, Long offeringId, LocalDate sessionDate, LocalTime startTime, int durationMinutes, String kind,
                              String topic, String recordedByName, OffsetDateTime recordedAt, boolean editable, CheckInState checkIn,
                              List<RegisterLine> register) {
    }

    // ------------------------------------------------------------------ self check-in (FR-TCH-03)

    public record CheckInRequest(@NotNull ClassSession.Kind kind, @Size(max = 200) String topic,
                                 @NotNull @Min(15) @Max(600) Integer durationMinutes,
                                 @NotNull @Min(3) @Max(60) Integer openMinutes, @NotNull @Min(0) @Max(120) Integer lateAfterMinutes) {
    }

    /** What the lecturer's screen shows while check-in is open: the current code (and a QR code of the check-in link), refreshed often. */
    public record CheckInScreen(Long sessionId, String moduleCode, String moduleTitle, String code, int secondsLeft, int stepSeconds,
                                String checkInUrl, String qrPng, CheckInState state, List<RegisterLine> recent) {
    }

    public record StudentCheckIn(@NotBlank @Size(min = 6, max = 6) String code, @Size(max = 64) String deviceId) {
    }

    public record CheckInDone(Long sessionId, String moduleCode, String moduleTitle, String status, OffsetDateTime checkedInAt,
                              boolean already) {
    }

    /** A student's attendance in one module so far. */
    public record StudentAttendance(Long moduleRegistrationId, Long studentId, String registrationNo, String name, String programmeCode,
                                    int counted, int attended, int absent, int excused, BigDecimal percent, boolean below,
                                    OffsetDateTime alertedAt, Boolean eligible, boolean exempted) {
    }

    public record AttendanceView(Long offeringId, Long semesterId, String semesterName, String code, String title, String departmentName,
                                 String status, List<String> lecturers, BigDecimal threshold, int sessions, int minutesTaught,
                                 BigDecimal averagePercent, int below, boolean canRecord, List<SessionSummary> sessionList,
                                 List<StudentAttendance> students) {
    }

    public record OfferingAttendance(Long offeringId, String code, String title, String departmentName, List<String> lecturers,
                                     int students, int sessions, int minutesTaught, BigDecimal averagePercent, int below, boolean mine,
                                     LocalDate lastSession) {
    }

    public record WorkloadModule(Long offeringId, String code, String title, BigDecimal credits, int students, int sessions, int minutesTaught,
                                 int sharedWith) {
    }

    public record Workload(Long userId, String fullName, List<String> roles, int modules, BigDecimal credits, int students, int sessions,
                           BigDecimal hoursTaught, List<WorkloadModule> offerings) {
    }

    public record WorkloadView(Long semesterId, List<Workload> staff, List<WorkloadModule> unallocated) {
    }

    // ------------------------------------------------------------------ examination eligibility (FR-EXM-04, BRU-08)

    public record EligibilityRules(BigDecimal minAttendancePercent, boolean requiresCa, boolean requiresClearance) {
    }

    public record GenerateResult(int offerings, int students, int eligible, int notEligible, int skipped, List<String> notes) {
    }

    public record OfferingEligibility(Long offeringId, String code, String title, String departmentName, String status, int students,
                                      int eligible, int notEligible, int exempted, int pendingExemptions, OffsetDateTime generatedAt,
                                      String generatedByName) {
    }

    public record EligibilityOverview(Long semesterId, EligibilityRules rules, boolean canGenerate, boolean canDecide,
                                      List<OfferingEligibility> offerings) {
    }

    public record EligibilityLine(Long moduleRegistrationId, Long studentId, String registrationNo, String name, String programmeCode,
                                  BigDecimal attendancePercent, int sessionsCounted, int sessionsAttended, boolean attendanceMet,
                                  boolean caMet, boolean clearanceMet, boolean rulesMet, boolean exempted, boolean eligible,
                                  List<String> reasons, ExemptionView exemption) {
    }

    public record OfferingEligibilityView(Long offeringId, Long semesterId, String semesterName, String code, String title,
                                          String departmentName, String status, EligibilityRules rules, OffsetDateTime generatedAt,
                                          String generatedByName, boolean canRequest, boolean canDecide, List<EligibilityLine> students) {
    }

    public record ExemptionRequest(@NotNull Long moduleRegistrationId, @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record DecisionNote(@Size(max = 1000) String note) {
    }

    public record ExemptionView(Long id, Long offeringId, String moduleCode, Long moduleRegistrationId, Long studentId, String registrationNo,
                                String studentName, String reason, String status, String requestedByName, OffsetDateTime requestedAt,
                                String decidedByName, OffsetDateTime decidedAt, String decisionNote, List<String> reasonsNotMet,
                                boolean canDecide) {
    }

    // ------------------------------------------------------------------ the lecturer's own teaching (BP-07, BP-08 steps 1-3)

    public record TeachingSemester(Long id, String name, String academicYear, LocalDate startDate, LocalDate endDate) {
    }

    public record MyModule(Long offeringId, String code, String title, BigDecimal credits, String departmentName, List<String> coLecturers,
                           List<String> programmes, int students, int sessions, int minutesTaught, LocalDate lastSession,
                           BigDecimal averagePercent, int below, String status, String returnedNote, String returnedByName, boolean planSet,
                           BigDecimal caPercent, BigDecimal sePercent, int complete, boolean eligibilityGenerated, int notEligible,
                           String resitStatus, int resitsToMark, String resitReturnedNote, Long openCheckInSessionId, boolean canRecord) {
    }

    public record AtRisk(Long offeringId, String moduleCode, Long studentId, String registrationNo, String name, BigDecimal percent, int attended,
                         int counted, boolean warned) {
    }

    public record AssignedRemark(Long id, String moduleCode, String moduleTitle, String kind, OffsetDateTime assignedAt) {
    }

    public record Step(String severity, String text, String link) {
    }

    public record MyTeaching(List<TeachingSemester> semesters, TeachingSemester semester, LocalDate marksEntryOpens, LocalDate marksEntryCloses,
                             String marksWindow, Integer daysToClose, BigDecimal threshold, int students, int sessions, BigDecimal hoursTaught,
                             List<MyModule> modules, List<tz.co.msingi.cims.exams.competence.CompetenceDtos.UnitSummary> competence,
                             List<AtRisk> atRisk, List<AssignedRemark> remarks, List<Step> next) {
    }

    // ------------------------------------------------------------------ student portal

    public record MyModuleAttendance(Long offeringId, String code, String title, int counted, int attended, int absent, int excused,
                                     BigDecimal percent, boolean below, Boolean eligible, boolean exempted, List<String> reasons) {
    }

    public record MyAttendance(Long semesterId, String semesterName, BigDecimal threshold, boolean listPublished,
                               List<MyModuleAttendance> modules) {
    }
}
