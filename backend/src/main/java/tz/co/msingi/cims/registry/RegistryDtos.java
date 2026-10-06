package tz.co.msingi.cims.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class RegistryDtos {

    private RegistryDtos() {
    }

    public record PatternRequest(@NotBlank @Size(max = 80) String pattern) {
    }

    public record SettingsView(String registrationNoPattern, boolean usingDefault, String example) {
    }

    public record HandoverRequest(@Size(max = 2000) List<@NotNull Long> applicationIds) {
    }

    public record HandoverRow(Long applicationId, String referenceNo, String applicantName, String programmeName,
                              String letterNo, OffsetDateTime acceptedAt, Long studentId, String registrationNo, String studentStatus) {
    }

    public record HandoverOverview(Long windowId, String windowName, long accepted, long handedOver, long pending,
                                   String registrationNoPattern, String registrationNoExample, List<HandoverRow> rows) {
    }

    public record HandoverResult(int created, List<HandoverRow> students) {
    }

    public record StudentSummary(Long id, String registrationNo, String fullName, String sex, String phone, Long programmeId,
                                 String programmeCode, String programmeName, String academicYear, String intake, int yearOfStudy,
                                 String status) {
        static StudentSummary of(Student s) {
            return new StudentSummary(s.getId(), s.getRegistrationNo(), s.fullName(), s.getSex(), s.getPhone(), s.getProgramme().getId(),
                    s.getProgramme().getCode(), s.getProgramme().getName(), s.getEntryAcademicYear().getCode(), s.getIntake().getName(),
                    s.getYearOfStudy(), s.getStatus().name());
        }
    }

    public record FirstRegistrationView(String status, List<FirstRegistration.DocumentCheck> documents, OffsetDateTime reportedAt,
                                        String remarks, String checkedByName, OffsetDateTime approvedAt, String approvedByName) {
        static FirstRegistrationView of(FirstRegistration f) {
            return f == null ? null : new FirstRegistrationView(f.getStatus().name(), f.getDocuments(), f.getReportedAt(), f.getRemarks(),
                    f.getCheckedByName(), f.getApprovedAt(), f.getApprovedByName());
        }
    }

    public record StudentDetail(StudentSummary summary, String firstName, String middleName, String surname, LocalDate dateOfBirth,
                                String nationality, String email, String region, String district, String postalAddress,
                                String disability, String nextOfKinName, String nextOfKinPhone, String nextOfKinRelation,
                                String awardName, String applicationRef, String nactvetVerificationNo, String admissionLetterNo,
                                OffsetDateTime admittedAt, String admittedByName, FirstRegistrationView firstRegistration) {
    }

    public record ChecklistRequest(@NotNull @Size(max = 30) List<@Valid DocumentCheckRequest> documents, @Size(max = 500) String remarks) {
    }

    public record DocumentCheckRequest(@NotBlank @Size(max = 200) String item, boolean verified) {
    }

    public record RegistryStats(Map<String, Long> byStatus) {
    }
}
