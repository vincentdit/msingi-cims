package tz.co.msingi.cims.graduation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class GraduationDtos {

    private GraduationDtos() {
    }

    // ------------------------------------------------------------------ offices

    public record OfficeRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code, @NotBlank @Size(max = 120) String name,
                                @NotNull ClearanceOffice.Kind kind, @Size(max = 500) String instructions, @Min(0) @Max(999) Integer sortOrder,
                                Boolean active, List<Long> staffUserIds) {
    }

    public record Person(Long id, String fullName) {
    }

    public record StaffOption(Long id, String fullName, List<String> roles) {
    }

    public record OfficeView(Long id, String code, String name, String kind, String instructions, int sortOrder, boolean active, List<Person> staff,
                             long pending, long held, boolean canDecide) {
    }

    // ------------------------------------------------------------------ clearance (FR-GRD-01, UC-18)

    public record ItemView(Long id, Long officeId, String officeCode, String officeName, String kind, String instructions, String status,
                           String note, String decidedByName, OffsetDateTime decidedAt, boolean canDecide) {
    }

    public record AwardView(boolean finalist, boolean qualified, String reason, String awardName, String classification, BigDecimal cgpa,
                            BigDecimal creditsEarned, String completedSemester, int semestersCompleted, int semestersRequired) {
        static AwardView of(Awards.Outcome o) {
            return new AwardView(o.finalist(), o.qualified(), o.reason(), o.awardName(), o.classification(), o.cgpa(), o.creditsEarned(),
                    o.completedSemester(), o.semestersCompleted(), o.semestersRequired());
        }
    }

    public record ClearanceView(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, String programmeName,
                                String status, String startedByName, OffsetDateTime startedAt, OffsetDateTime clearedAt, int cleared, int total,
                                List<ItemView> items) {
    }

    public record ClearanceSummary(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, String status,
                                   OffsetDateTime startedAt, OffsetDateTime clearedAt, int cleared, int held, int total) {
    }

    public record QueueLine(Long itemId, Long clearanceId, Long studentId, String registrationNo, String studentName, String programmeCode,
                            String departmentName, String status, String note, String decidedByName, OffsetDateTime decidedAt,
                            OffsetDateTime startedAt, boolean canDecide) {
    }

    public record Finalist(Long studentId, String registrationNo, String studentName, String programmeCode, AwardView award,
                           String clearanceStatus, Long clearanceId, String graduation) {
    }

    public record DecisionRequest(@NotNull ClearanceItem.Status status, @Size(max = 500) String note) {
    }

    public record StartRequest(@NotNull Long studentId) {
    }

    public record MyClearance(AwardView award, ClearanceView clearance, MyGraduation graduation) {
    }

    public record MyGraduation(String name, LocalDate ceremonyDate, String status, String awardName, String classification) {
    }

    // ------------------------------------------------------------------ graduations (FR-GRD-02/03)

    public record GraduationRequest(@NotBlank @Size(max = 120) String name, LocalDate ceremonyDate) {
    }

    public record GraduationSummary(Long id, String name, LocalDate ceremonyDate, String status, int graduands, int certificates,
                                    String nactvetStatus, OffsetDateTime createdAt) {
    }

    public record GraduandView(Long id, Long studentId, String registrationNo, String studentName, String sex, String programmeCode,
                               String programmeName, String awardName, String classification, BigDecimal cgpa, BigDecimal creditsEarned,
                               String completedSemester, String certificateNo, String verificationCode, OffsetDateTime certificateIssuedAt,
                               int printCount, OffsetDateTime lastPrintedAt) {
    }

    public record NotReady(Long studentId, String registrationNo, String studentName, String programmeCode, String reason) {
    }

    public record GraduationPermissions(boolean edit, boolean generate, boolean submit, boolean approve, boolean issue, boolean nactvet) {
    }

    public record GraduationView(Long id, String name, LocalDate ceremonyDate, String status, String createdByName, String generatedByName,
                                 OffsetDateTime generatedAt, String submittedByName, OffsetDateTime submittedAt, String approvedByName,
                                 OffsetDateTime approvedAt, String boardMinuteRef, String returnedNote, String returnedByName,
                                 String nactvetStatus, String nactvetReference, String nactvetNote, OffsetDateTime nactvetSubmittedAt,
                                 List<GraduandView> graduands, List<NotReady> notReady, GraduationPermissions can) {
    }

    public record GenerateResult(int added, int removed, int total, List<NotReady> notReady) {
    }

    public record ApproveRequest(@NotBlank @Size(max = 100) String boardMinuteRef) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record NactvetRequest(@NotNull Graduation.NactvetStatus status, @Size(max = 100) String reference, @Size(max = 1000) String note) {
    }

    public record IssueResult(int issued, int alreadyIssued) {
    }

    public record ReprintRequest(@NotBlank @Size(min = 5, max = 300) String reason) {
    }

    public record CertificateVerification(String documentType, String status, String institution, String institutionNactvetNo, String letterNo,
                                          int edition, OffsetDateTime issuedAt, String holderName, String registrationNo, String programme,
                                          String award, String classification, String graduation, LocalDate ceremonyDate) {
    }
}
