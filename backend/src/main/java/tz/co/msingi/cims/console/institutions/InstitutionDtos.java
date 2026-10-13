package tz.co.msingi.cims.console.institutions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery.SystemHealth;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class InstitutionDtos {

    private InstitutionDtos() {
    }

    static final String CODE_RULE = "^[A-Za-z][A-Za-z0-9]{1,19}$";
    static final String USERNAME_RULE = "^[A-Za-z0-9._-]+$";
    static final String COLOUR_RULE = "^#[0-9a-fA-F]{6}$";

    public record AdminRequest(@NotBlank @Size(min = 3, max = 60) @Pattern(regexp = USERNAME_RULE,
            message = "Use letters, digits, dot, dash or underscore") String username,
                               @NotBlank @Size(max = 150) String fullName,
                               @Email @Size(max = 120) String email,
                               @Size(max = 20) String phone) {
    }

    public record OnboardRequest(@NotBlank @Pattern(regexp = CODE_RULE, message = "2 to 20 letters and digits, starting with a letter") String code,
                                 @NotBlank @Size(max = 200) String name,
                                 @Size(max = 60) String shortName,
                                 @Size(max = 40) String nactvetRegNo,
                                 @NotBlank @Pattern(regexp = "PUBLIC|PRIVATE") String ownership,
                                 @Email @Size(max = 120) String contactEmail,
                                 @Size(max = 30) String contactPhone,
                                 @Pattern(regexp = COLOUR_RULE, message = "A colour like #1F4E8C") String primaryColor,
                                 @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 190) String> domains,
                                 @NotNull @Valid AdminRequest admin,
                                 @Size(max = 1000) String notes) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 200) String name,
                                @Size(max = 60) String shortName,
                                @Size(max = 40) String nactvetRegNo,
                                @NotBlank @Pattern(regexp = "PUBLIC|PRIVATE") String ownership,
                                @Email @Size(max = 120) String contactEmail,
                                @Size(max = 30) String contactPhone,
                                @Pattern(regexp = COLOUR_RULE, message = "A colour like #1F4E8C") String primaryColor,
                                @Size(max = 1000) String notes) {
    }

    public record DomainRequest(@NotBlank @Size(max = 190) String host, @Pattern(regexp = "ALL|ADMISSION|STUDENT|STAFF") String purpose) {
    }

    public record StatusRequest(@Size(max = 300) String reason) {
    }

    public record SmsCreditRequest(@Positive @Max(10_000_000) int units, @NotNull @DecimalMin("0") BigDecimal amountPaid,
                                   @NotBlank @Size(max = 80) String reference) {
    }

    public record DomainView(Long id, String host, String purpose, OffsetDateTime createdAt) {
    }

    public record AdminView(Long id, String username, String fullName, String email, String phone, String status,
                            boolean mustChangePassword, OffsetDateTime lastLoginAt) {
    }

    /** What the institution uses: people, records and SMS. */
    public record Usage(long studentsActive, long studentsTotal, long applicationsThisYear, long staffActive, long staffTotal,
                        long programmes, long smsBalance, long smsPartsThisMonth, OffsetDateTime lastStaffSignIn) {
    }

    public record SetupStep(String key, String label, boolean done, String hint) {
    }

    public record InstitutionRow(Long id, String code, String name, String shortName, String status, String statusReason, String ownership,
                                 String nactvetRegNo, String primaryHost, OffsetDateTime createdAt, Usage usage, int setupDone, int setupTotal,
                                 long integrationFailures24h) {
    }

    public record InstitutionDetail(Long id, String code, String name, String shortName, String nactvetRegNo, String ownership,
                                    String status, String statusReason, OffsetDateTime statusChangedAt, String statusChangedBy,
                                    String contactEmail, String contactPhone, String primaryColor, String notes, String onboardedBy,
                                    OffsetDateTime createdAt, String staffSignInUrl, List<DomainView> domains, List<AdminView> administrators,
                                    Usage usage, List<SetupStep> setup, List<SystemHealth> health,
                                    List<PlatformAuditService.Entry> recentActions) {
    }

    /** A new institution, or a password issued to one of its administrators: the temporary password is shown once. */
    public record IssuedAdmin(InstitutionDetail institution, String username, String temporaryPassword, String signInUrl) {
    }

    public record Overview(long institutions, long active, long suspended, long studentsActive, long staffActive, long applicationsThisYear,
                           List<SystemHealth> health, List<InstitutionRow> attention, List<PlatformAuditService.Entry> recentActions) {
    }
}
