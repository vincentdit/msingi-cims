package tz.co.msingi.cims.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class MessagingDtos {

    private MessagingDtos() {
    }

    // ------------------------------------------------------------------ settings, bundle

    public record SettingsRequest(@NotNull @Pattern(regexp = "EN|SW") String defaultLanguage,
                                  @Pattern(regexp = "^[A-Za-z0-9 ]{0,11}$", message = "up to 11 letters, digits or spaces") String senderId,
                                  @NotNull @DecimalMin("0") BigDecimal pricePerSms, @Min(0) int lowBalanceWarning) {
    }

    public record TopupRequest(@Positive @Max(10_000_000) int units, @NotNull @DecimalMin("0") BigDecimal amountPaid,
                               @NotBlank @Size(max = 80) String reference) {
    }

    public record TopupView(Long id, int units, BigDecimal amountPaid, String reference, OffsetDateTime recordedAt, String recordedByName) {
    }

    public record CountRow(String key, long messages, long parts) {
    }

    public record Overview(String defaultLanguage, String senderId, BigDecimal pricePerSms, int lowBalanceWarning, long purchased, long used,
                           long balance, boolean lowBalance, List<CountRow> thisMonthByStatus, List<CountRow> thisMonthByKind,
                           BigDecimal thisMonthCost, Double deliveryRate, List<TopupView> topups, String gatewayMode) {
    }

    // ------------------------------------------------------------------ templates

    public record TemplateRequest(@NotBlank @Size(max = 700) String bodyEn, @NotBlank @Size(max = 700) String bodySw, boolean enabled) {
    }

    public record PreviewRequest(@NotBlank @Size(max = 700) String text) {
    }

    public record TemplateView(String code, String name, String when, String group, List<String> variables, List<String> required,
                               String bodyEn, String bodySw, boolean enabled, boolean customised, String defaultEn, String defaultSw,
                               String sampleEn, String sampleSw, int partsEn, int partsSw, String updatedByName, OffsetDateTime updatedAt) {
    }

    // ------------------------------------------------------------------ bulk messages

    public record Audience(@NotNull @Pattern(regexp = "STUDENTS|APPLICANTS|PHONES") String group,
                           List<String> studentStatuses, Long programmeId, @Min(1) @Max(8) Integer yearOfStudy, boolean withFeeBalance,
                           Long windowId, List<String> applicationStatuses, @Size(max = 60_000) String phones) {
    }

    public record CampaignRequest(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 700) String body, @NotNull @Valid Audience audience) {
    }

    public record Sample(String name, String phone, String text, int parts, String encoding) {
    }

    public record CampaignPreview(String audienceLabel, int recipients, int skippedNoPhone, int skippedDuplicates, List<String> invalidPhones,
                                  List<String> placeholders, List<Sample> samples, int totalParts, BigDecimal pricePerSms,
                                  BigDecimal estimatedCost, long balance, boolean enoughBalance, boolean unicode) {
    }

    public record CampaignView(Long id, String title, String body, String audienceLabel, int recipients, int smsParts, BigDecimal estimatedCost,
                               OffsetDateTime sentAt, String sentByName, Map<String, Long> byStatus) {
    }

    public record MessageView(Long id, String phone, String recipientName, String kind, String templateCode, Long campaignId, String language,
                              String body, int parts, String encoding, String status, int attempts, String error, OffsetDateTime queuedAt,
                              OffsetDateTime sentAt, OffsetDateTime deliveredAt) {
        static MessageView of(Message m) {
            return new MessageView(m.getId(), m.getPhone(), m.getRecipientName(), m.getKind().name(), m.getTemplateCode(), m.getCampaignId(),
                    m.getLanguage(), m.getBody(), m.getParts(), m.getEncoding(), m.getStatus().name(), m.getAttempts(), m.getError(),
                    m.getQueuedAt(), m.getSentAt(), m.getDeliveredAt());
        }
    }

    public record CampaignDetail(CampaignView campaign, List<MessageView> messages) {
    }
}
