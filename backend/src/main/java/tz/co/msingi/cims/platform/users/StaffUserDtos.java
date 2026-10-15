package tz.co.msingi.cims.platform.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.admission.service.AdmissionDtos;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.users.RoleCatalog.Scope;

import java.time.OffsetDateTime;
import java.util.List;

public final class StaffUserDtos {

    private StaffUserDtos() {
    }

    public record RoleGrant(@NotNull Role role, Scope scope, Long scopeId) {
    }

    public record RoleGrantView(Role role, String label, Scope scope, Long scopeId, String scopeName) {
    }

    public record StaffUserView(Long id, String username, String fullName, String jobTitle, String email, String phone, String status,
                                boolean mustChangePassword, OffsetDateTime lastLoginAt, OffsetDateTime createdAt, String createdByName,
                                List<RoleGrantView> roles, boolean self, boolean mfaEnabled) {
    }

    public record StaffUserRequest(
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Use letters, digits, dots, dashes or underscores") String username,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 120) String jobTitle,
            @Email @Size(max = 120) String email,
            @Pattern(regexp = "^(\\+?255|0)[67]\\d{8}$", message = "Enter a Tanzanian mobile number, e.g. 0712345678") String phone,
            @NotEmpty(message = "Give the user at least one role") List<@Valid RoleGrant> roles) {
    }

    /** A new account or a reset: the temporary password is shown once, to hand to the user. */
    public record IssuedPassword(StaffUserView user, String temporaryPassword) {
    }

    public record StatusRequest(@Size(max = 300) String reason) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Pattern(regexp = AdmissionDtos.PASSWORD_RULE, message = AdmissionDtos.PASSWORD_MESSAGE) @tz.co.msingi.cims.platform.security.NotBreached String newPassword) {
    }

    public record Summary(long total, long active, long disabled, long locked, long mustChangePassword) {
    }
}
