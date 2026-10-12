package tz.co.msingi.cims.admission.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.letter.JoiningInstructions;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.ApplicationChoice;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EligibilityResult;
import tz.co.msingi.cims.admission.eligibility.EntryRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AdmissionDtos {

    private AdmissionDtos() {
    }

    public static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE = "Use at least 8 characters with letters and numbers";

    // ------------------------------------------------------------------ applicant auth
    /**
     * Registration with the Form Four (CSEE) index number and year; names and sex are taken from NECTA, not typed.
     * The year may also be typed with the index number (S0101/0001/2023).
     */
    public record RegisterRequest(
            @NotBlank @Size(max = 25) String indexNumber,
            Integer examYear,
            @NotBlank String phone,
            @Email @Size(max = 120) String email,
            @NotBlank @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password,
            @AssertTrue(message = "You must accept the privacy notice to apply") boolean acceptPrivacyNotice) {
    }

    public record NectaLookupRequest(@NotBlank @Size(max = 25) String indexNumber, Integer examYear) {
    }

    /** What NECTA holds for an index number, shown to the applicant to confirm before the account is created. */
    public record NectaCandidateView(String indexNumber, int examYear, String firstName, String middleName, String surname, String fullName,
                                     String sex, String centreName, String division, Integer points, List<SubjectView> subjects,
                                     boolean alreadyRegistered) {
    }

    public record RegisterResponse(Long userId, String maskedPhone, String message, String devOtp, String fullName) {
    }

    public record VerifyRequest(@NotBlank String phone, @NotBlank @Pattern(regexp = "\\d{4,8}") String code) {
    }

    public record PhoneRequest(@NotBlank String phone) {
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {
    }

    public record ResetPasswordRequest(@NotBlank String phone, @NotBlank String code,
                                       @NotBlank @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record OtpSentResponse(String maskedPhone, String message, String devOtp) {
    }

    // ------------------------------------------------------------------ applicant profile
    public record ProfileRequest(
            @NotBlank @Size(max = 60) String firstName,
            @Size(max = 60) String middleName,
            @NotBlank @Size(max = 60) String surname,
            @Pattern(regexp = "[MF]", message = "Use M or F") String sex,
            @Past LocalDate dateOfBirth,
            @Size(max = 60) String nationality,
            @Email String email,
            @Size(max = 60) String region,
            @Size(max = 60) String district,
            @Size(max = 200) String postalAddress,
            @Size(max = 120) String disability,
            @Size(max = 150) String nextOfKinName,
            String nextOfKinPhone,
            @Size(max = 40) String nextOfKinRelation) {
    }

    public record ProfileView(Long id, String firstName, String middleName, String surname, String fullName, String sex,
                              LocalDate dateOfBirth, String nationality, String phone, String email, String region,
                              String district, String postalAddress, String disability, String nextOfKinName,
                              String nextOfKinPhone, String nextOfKinRelation, List<String> missingFields,
                              String nectaIndexNumber, Short nectaExamYear, boolean namesFromNecta) {
        public static ProfileView of(Applicant a) {
            return new ProfileView(a.getId(), a.getFirstName(), a.getMiddleName(), a.getSurname(), a.fullName(), a.getSex(),
                    a.getDateOfBirth(), a.getNationality(), a.getPhone(), a.getEmail(), a.getRegion(), a.getDistrict(),
                    a.getPostalAddress(), a.getDisability(), a.getNextOfKinName(), a.getNextOfKinPhone(),
                    a.getNextOfKinRelation(), a.missingProfileFields(), a.getNectaIndexNumber(), a.getNectaExamYear(), a.isNamesFromNecta());
        }
    }

    // ------------------------------------------------------------------ qualifications
    public record NectaFetchRequest(
            @NotNull ApplicantQualification.Type type,
            @NotBlank @Pattern(regexp = "^[SP]\\d{4}/\\d{4}$", message = "Use the NECTA format, e.g. S0101/0001") String indexNumber,
            @Min(1988) @Max(2100) int year) {
    }

    public record PriorAwardRequest(
            @NotNull ApplicantQualification.Type type,
            @NotBlank @Size(max = 40) String number,
            @Min(1) @Max(8) short level,
            @Min(1990) @Max(2100) Short year) {
    }

    public record SubjectView(String code, String name, String grade) {
    }

    public record QualificationView(Long id, String type, String indexNumber, Short examYear, Short awardLevel,
                                    String centreName, String division, Short points, String source, boolean verified,
                                    List<SubjectView> subjects, String candidateName, boolean registration) {
        public static QualificationView of(ApplicantQualification q) {
            Applicant a = q.getApplicant();
            boolean registration = q.getType() == ApplicantQualification.Type.CSEE && a != null && q.getIndexNumber().equals(a.getNectaIndexNumber())
                    && q.getExamYear() != null && q.getExamYear().equals(a.getNectaExamYear());
            return new QualificationView(q.getId(), q.getType().name(), q.getIndexNumber(), q.getExamYear(), q.getAwardLevel(),
                    q.getCentreName(), q.getDivision(), q.getPoints(), q.getSource().name(), q.isVerified(),
                    q.getSubjects().stream().map(s -> new SubjectView(s.getSubjectCode(), s.getSubjectName(), s.getGrade())).toList(),
                    q.getCandidateName(), registration);
        }
    }

    // ------------------------------------------------------------------ windows
    public record WindowRequest(
            @NotNull Long academicYearId,
            @NotNull Long intakeId,
            @NotBlank @Size(max = 150) String name,
            @NotNull OffsetDateTime opensAt,
            @NotNull OffsetDateTime closesAt,
            @NotNull @PositiveOrZero BigDecimal applicationFee,
            @Min(1) @Max(10) short maxChoices,
            @Min(1) @Max(90) Short offerAcceptanceDays,
            String instructions) {
    }

    public record WindowProgrammeRequest(@NotNull Long programmeId, @Min(1) int capacity, @NotNull @Valid EntryRules entryRules) {
    }

    public record WindowProgrammeView(Long id, Long programmeId, String programmeCode, String programmeName, String awardFamily,
                                      short awardLevel, String awardName, String department, int capacity,
                                      String requirementSummary, EntryRules entryRules) {
        public static WindowProgrammeView of(WindowProgramme wp) {
            var p = wp.getProgramme();
            return new WindowProgrammeView(wp.getId(), p.getId(), p.getCode(), p.getName(), p.getAwardFamily().name(),
                    p.getAwardLevel(), p.getAwardName(), p.getDepartment().getName(), wp.getCapacity(),
                    wp.getEntryRules() == null ? null : wp.getEntryRules().summary(), wp.getEntryRules());
        }
    }

    public record WindowView(Long id, String name, Long academicYearId, String academicYear, Long intakeId, String intake,
                             OffsetDateTime opensAt, OffsetDateTime closesAt, BigDecimal applicationFee, short maxChoices,
                             short offerAcceptanceDays, String status, boolean open, String instructions,
                             JoiningInstructions joiningInstructions, List<WindowProgrammeView> programmes, String state) {
        public static WindowView of(AdmissionWindow w, List<WindowProgramme> programmes) {
            return new WindowView(w.getId(), w.getName(), w.getAcademicYear().getId(), w.getAcademicYear().getCode(),
                    w.getIntake().getId(), w.getIntake().getName(), w.getOpensAt(), w.getClosesAt(), w.getApplicationFee(),
                    w.getMaxChoices(), w.getOfferAcceptanceDays(), w.getStatus().name(), w.isOpen(OffsetDateTime.now()), w.getInstructions(),
                    w.getJoiningInstructions(),
                    programmes == null ? null : programmes.stream().map(WindowProgrammeView::of).toList(), w.state(OffsetDateTime.now()));
        }
    }

    /** Open a window now; optionally with a new closing date. */
    public record OpenRequest(OffsetDateTime closesAt) {
    }

    public record CloseRequest(@NotBlank @Size(max = 500) String reason, Boolean notifyApplicants) {
    }

    public record ReopenRequest(@NotNull OffsetDateTime closesAt, @NotBlank @Size(max = 500) String reason, Boolean notifyApplicants) {
    }

    public record WindowEventView(Long id, String action, String reason, OffsetDateTime opensAt, OffsetDateTime closesAt,
                                  OffsetDateTime previousClosesAt, int applicantsNotified, boolean automatic, String byName, OffsetDateTime at) {
    }

    public record WindowStats(Long windowId, long total, long draft, long paymentPending, long submitted) {
    }

    // ------------------------------------------------------------------ applications
    public record StartApplicationRequest(@NotNull Long windowId) {
    }

    public record AcceptOfferRequest(@Size(max = 20) String confirmationCode) {
    }

    public record DeclineOfferRequest(@Size(max = 300) String reason) {
    }

    public record ChoicesRequest(@NotEmpty @Size(max = 10) List<@NotNull Long> windowProgrammeIds) {
    }

    public record ChoiceView(Long id, short order, Long windowProgrammeId, String programmeCode, String programmeName,
                             String awardName, boolean eligible, EligibilityResult eligibility) {
        public static ChoiceView of(ApplicationChoice c) {
            var p = c.getWindowProgramme().getProgramme();
            return new ChoiceView(c.getId(), c.getChoiceOrder(), c.getWindowProgramme().getId(), p.getCode(), p.getName(),
                    p.getAwardName(), c.isEligible(), c.getEligibilityDetail());
        }
    }

    public record FeeView(Long invoiceId, String invoiceNo, BigDecimal amount, BigDecimal paid, String invoiceStatus,
                          String controlNo, OffsetDateTime controlNoExpiresAt, boolean feePaid) {
    }

    public record ApplicationView(Long id, String referenceNo, String status, Long windowId, String windowName,
                                  String academicYear, String intake, OffsetDateTime windowClosesAt, BigDecimal applicationFee,
                                  short maxChoices, boolean feePaid, OffsetDateTime submittedAt, List<ChoiceView> choices,
                                  List<String> blockers, String selectedProgramme, OfferView offer, LetterView letter,
                                  StudentInfo student) {
        public static ApplicationView of(Application a, List<String> blockers, String devConfirmationCode, LetterView letter,
                                         StudentInfo student) {
            AdmissionWindow w = a.getWindow();
            return new ApplicationView(a.getId(), a.getReferenceNo(), a.getStatus().name(), w.getId(), w.getName(),
                    w.getAcademicYear().getCode(), w.getIntake().getName(), w.getClosesAt(), w.getApplicationFee(),
                    w.getMaxChoices(), a.isFeePaid(), a.getSubmittedAt(),
                    a.getChoices().stream().map(ChoiceView::of).toList(), blockers,
                    a.getSelectedWindowProgramme() == null ? null : a.getSelectedWindowProgramme().getProgramme().getName(),
                    OfferView.of(a, devConfirmationCode), letter, student);
        }
    }

    /** Once Registry has handed the applicant over: the registration number and student status (BP-05). */
    public record StudentInfo(String registrationNo, String status) {
    }

    /** The applicant's valid admission letter, once issued (FR-ADM-13). */
    public record LetterView(String letterNo, int edition, String verificationCode, OffsetDateTime issuedAt) {
    }

    /**
     * The applicant's admission offer (BP-04), present once NACTVET has given an outcome.
     * The rejection reason is shown only for NACTVET rejections; queries are handled by the admissions office.
     */
    public record OfferView(String verificationNo, OffsetDateTime verifiedAt, boolean multipleAdmission,
                            OffsetDateTime expiresAt, boolean open, OffsetDateTime respondedAt, String rejectionReason,
                            int confirmationAttemptsLeft, String devConfirmationCode) {
        static OfferView of(Application a, String devConfirmationCode) {
            boolean rejected = a.getStatus() == Application.Status.NACTVET_REJECTED;
            if (a.getOfferIssuedAt() == null && !rejected) return null;
            return new OfferView(a.getNactvetVerificationNo(), a.getVerifiedAt(), a.isMultipleAdmission(), a.getOfferExpiresAt(),
                    a.isOfferOpen(OffsetDateTime.now()), a.getOfferRespondedAt(), rejected ? a.getVerificationReason() : null,
                    Math.max(0, tz.co.msingi.cims.admission.offer.OfferService.MAX_CONFIRMATION_ATTEMPTS - a.getConfirmationAttempts()),
                    devConfirmationCode);
        }
    }

    public record ProgrammeEligibilityView(Long windowProgrammeId, String programmeCode, String programmeName, String awardName,
                                           String department, int capacity, boolean eligible, EligibilityResult eligibility) {
    }

    public record ApplicationSummaryView(Long id, String referenceNo, String status, String applicantName, String phone,
                                         Long windowId, String windowName, boolean feePaid, OffsetDateTime submittedAt,
                                         OffsetDateTime createdAt) {
        public static ApplicationSummaryView of(Application a) {
            return new ApplicationSummaryView(a.getId(), a.getReferenceNo(), a.getStatus().name(), a.getApplicant().fullName(),
                    a.getApplicant().getPhone(), a.getWindow().getId(), a.getWindow().getName(), a.isFeePaid(),
                    a.getSubmittedAt(), a.getCreatedAt());
        }
    }

    public record ApplicationDetailView(ApplicationView application, ProfileView applicant, List<QualificationView> qualifications,
                                        FeeView fee) {
    }
}
