package tz.co.msingi.cims.registry.idcard;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class IdCardDtos {

    private IdCardDtos() {
    }

    public record CardView(Long id, String cardNo, String verificationCode, LocalDate validUntil, String status, String issuedByName,
                           OffsetDateTime issuedAt, String endedReason, OffsetDateTime endedAt) {
    }

    /** A student's photo and cards, and whether a card can be issued now (and if not, why). */
    public record IdCardSummary(boolean hasPhoto, String photoUpdatedAt, boolean canIssue, String issueBlocker, CardView current,
                                List<CardView> history) {
    }

    public record IssueRequest(@Size(max = 300) String reason) {
    }

    public record RevokeRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record BatchRequest(@NotNull Long programmeId, Short yearOfStudy) {
    }

    /** Who a batch would cover: active students with a photo and no valid card; the others are listed so Registry can follow up. */
    public record BatchPreview(int ready, int withoutPhoto, int alreadyCarded, List<String> missingPhoto) {
    }

    public record IdCardVerification(String documentType, String status, String institution, String institutionNactvetNo, String letterNo,
                                     OffsetDateTime issuedAt, String holderName, String registrationNo, String programme,
                                     LocalDate validUntil, String studentStatus) {
    }
}
