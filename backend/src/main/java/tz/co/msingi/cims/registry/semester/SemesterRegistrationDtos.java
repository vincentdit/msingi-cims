package tz.co.msingi.cims.registry.semester;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class SemesterRegistrationDtos {

    private SemesterRegistrationDtos() {
    }

    public record SubmitRequest(@NotNull Long semesterId, @NotNull List<Long> electiveModuleIds, boolean detailsConfirmed) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String note) {
    }

    public record ApproveAllRequest(@NotNull Long semesterId) {
    }

    public record SemesterInfo(Long id, String name, String academicYear, short semesterNo, LocalDate startDate, LocalDate endDate,
                               LocalDate registrationOpens, LocalDate registrationCloses, LocalDate lateRegistrationCloses, String window,
                               BigDecimal lateRegistrationFee) {
    }

    /** The late-registration fee charged for a registration made in the late period (BP-05). */
    public record LateFee(Long invoiceId, String invoiceNo, BigDecimal amount, BigDecimal balance, String status, String controlNo) {
    }

    public record ModuleOption(Long moduleId, String code, String title, BigDecimal credits, String category, String assessmentType, boolean selected) {
    }

    public record RegisteredModule(Long moduleId, String code, String title, BigDecimal credits, String category, String attempt) {
    }

    public record RegistrationView(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, String programmeName,
                                   Long semesterId, String semesterName, short yearOfStudy, String status, boolean late, BigDecimal totalCredits,
                                   Map<String, Object> clearance, boolean detailsConfirmed, OffsetDateTime submittedAt, String submittedByName,
                                   OffsetDateTime decidedAt, String decidedByName, String decisionNote, String curriculum,
                                   List<RegisteredModule> modules, LateFee lateFee) {
    }

    /**
     * What the student sees: the semester, whether they can register now (state), the modules of their curriculum for it,
     * the fee clearance and their registration if they have one.
     */
    /** @param healthCoverMissing the institution requires health insurance (NHIF) and the student is not covered for the semester's year */
    public record StudentRegistration(SemesterInfo semester, String state, String message, short yearOfStudy, String curriculum,
                                      List<ModuleOption> core, List<ModuleOption> electives, int minElectives, int maxElectives,
                                      Clearance clearance, RegistrationView registration, List<RegistrationView> history, boolean healthCoverMissing) {

        public StudentRegistration(SemesterInfo semester, String state, String message, short yearOfStudy, String curriculum,
                                   List<ModuleOption> core, List<ModuleOption> electives, int minElectives, int maxElectives,
                                   Clearance clearance, RegistrationView registration, List<RegistrationView> history) {
            this(semester, state, message, yearOfStudy, curriculum, core, electives, minElectives, maxElectives, clearance, registration, history, false);
        }

        StudentRegistration withHealthCoverMissing(boolean missing) {
            return new StudentRegistration(semester, state, message, yearOfStudy, curriculum, core, electives, minElectives, maxElectives, clearance,
                    registration, history, missing);
        }
    }

    public record SemesterSummary(Long semesterId, String semesterName, String window, long currentStudents, long notRegistered,
                                  Map<String, Long> byStatus) {
    }

    public record ApproveAllResult(int approved, List<String> skipped) {
    }
}
