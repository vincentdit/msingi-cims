package tz.co.msingi.cims.console.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.admission.service.AdmissionDtos;

import java.time.OffsetDateTime;

public final class PlatformAuthDtos {

    private PlatformAuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, Me user) {
    }

    public record Me(Long id, String username, String fullName, boolean mustChangePassword) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Pattern(regexp = AdmissionDtos.PASSWORD_RULE, message = AdmissionDtos.PASSWORD_MESSAGE) String newPassword) {
    }

    public record PlatformUserRequest(@NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
            message = "Use letters, digits, dot, dash or underscore") String username,
                                      @NotBlank @Size(max = 150) String fullName,
                                      @Email @Size(max = 120) String email,
                                      @Size(max = 20) String phone) {
    }

    public record PlatformUserView(Long id, String username, String fullName, String email, String phone, String status,
                                   boolean mustChangePassword, OffsetDateTime lastLoginAt, String createdByName, OffsetDateTime createdAt,
                                   boolean self) {
    }

    public record IssuedPassword(PlatformUserView user, String temporaryPassword) {
    }
}
