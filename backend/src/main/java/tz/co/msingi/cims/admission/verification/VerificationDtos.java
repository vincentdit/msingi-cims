package tz.co.msingi.cims.admission.verification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class VerificationDtos {

    private VerificationDtos() {
    }

    public record SubmitRequest(@NotNull NactvetSubmission.Channel channel) {
    }

    public record ResolveQueryRequest(@NotBlank @Size(max = 300) String remarks) {
    }

    /** Outcome file from NACTVET, sent as text (CSV). */
    public record ImportRequest(@NotBlank @Size(max = 2_000_000) String csv) {
    }

    /** A selected applicant awaiting submission, with anything that would make NACTVET query or reject the record. */
    public record ReadyRow(Long applicationId, String referenceNo, String applicantName, String programmeName,
                           String indexNumber, String remarks, List<String> issues) {
    }

    public record SubmissionView(Long id, int batchNo, String channel, String status, String externalRef,
                                 OffsetDateTime submittedAt, String submittedByName, OffsetDateTime lastOutcomeAt,
                                 OffsetDateTime completedAt, long total, long pending, long verified, long queried, long rejected) {
    }

    public record ApplicantRow(Long applicationId, String referenceNo, String applicantName, String phone, String sex,
                               String programmeName, String status, String indexNumber, String verificationNo,
                               boolean multipleAdmission, String reason, String remarks, OffsetDateTime verifiedAt,
                               OffsetDateTime offerExpiresAt, OffsetDateTime offerRespondedAt, String declineReason,
                               boolean offerExpired) {
    }

    public record Overview(Long windowId, short offerAcceptanceDays, String institutionNactvetNo,
                           Map<String, Long> counts, Double acceptanceRate,
                           List<ReadyRow> ready, List<SubmissionView> submissions, List<ApplicantRow> applicants) {
    }

    public record OutcomeResult(int applied, int verified, int queried, int rejected, SubmissionView submission) {
    }
}
