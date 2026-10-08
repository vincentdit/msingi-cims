package tz.co.msingi.cims.portal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class PortalDtos {

    private PortalDtos() {
    }

    // ------------------------------------------------------------------ sign-in

    public record StudentLoginRequest(@NotBlank @Size(max = 120) String login, @NotBlank @Size(max = 100) String password) {
    }

    // ------------------------------------------------------------------ notices

    public record NoticeRequest(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 4000) String body,
                                @NotNull Notice.Category category, Long programmeId, @Min(1) @Max(8) Short yearOfStudy,
                                boolean pinned, OffsetDateTime expiresAt) {
    }

    public record PublishRequest(OffsetDateTime publishAt, boolean sendSms) {
    }

    public record NoticeView(Long id, String title, String body, String category, Long programmeId, String programmeName, Short yearOfStudy,
                             String audience, boolean pinned, String status, OffsetDateTime publishAt, OffsetDateTime expiresAt,
                             boolean live, int smsSentCount, long audienceCount, long readCount, String createdByName,
                             String publishedByName, OffsetDateTime withdrawnAt, String withdrawnByName, OffsetDateTime createdAt) {
    }

    public record StudentNotice(Long id, String title, String body, String category, boolean pinned, OffsetDateTime publishAt,
                                String publishedByName, boolean read) {
    }

    // ------------------------------------------------------------------ profile and change requests (FR-REG-08)

    public record ChangeRequestSubmit(@NotEmpty Map<String, String> changes, @NotBlank @Size(max = 500) String reason) {
    }

    public record DecisionRequest(@Size(max = 500) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String note) {
    }

    public record ChangeView(String field, String label, String from, String to) {
    }

    public record ChangeRequestView(Long id, Long studentId, String registrationNo, String studentName, String programmeName, String status,
                                    List<ChangeView> changes, String reason, boolean needsDocuments, OffsetDateTime submittedAt,
                                    OffsetDateTime decidedAt, String decidedByName, String decisionNote) {
    }

    public record ProfileView(String registrationNo, String firstName, String middleName, String surname, String sex, LocalDate dateOfBirth,
                              String nationality, String phone, String email, String region, String district, String postalAddress,
                              String disability, String nextOfKinName, String nextOfKinPhone, String nextOfKinRelation,
                              String programmeName, String programmeCode, String awardName, String academicYear, String intake,
                              int yearOfStudy, String status, String nactvetVerificationNo, String admissionLetterNo,
                              Map<String, String> editableFields, List<ChangeRequestView> requests) {
    }

    // ------------------------------------------------------------------ dashboard

    public record NextPayment(Long invoiceId, String invoiceNo, String description, BigDecimal balance, LocalDate dueDate, String controlNo) {
    }

    public record Dashboard(String institutionName, String registrationNo, String fullName, String firstName, String programmeName,
                            String awardName, int yearOfStudy, String academicYear, String status, String firstRegistrationStatus,
                            BigDecimal billed, BigDecimal paid, BigDecimal balance, BigDecimal credit, NextPayment nextPayment,
                            List<Clearance> clearance, long unreadNotices, List<StudentNotice> latestNotices,
                            ChangeRequestView pendingRequest) {
    }
}
