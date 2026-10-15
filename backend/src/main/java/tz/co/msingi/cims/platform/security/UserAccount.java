package tz.co.msingi.cims.platform.security;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "user_accounts")
public class UserAccount extends BaseEntity {

    public enum UserType { STAFF, APPLICANT, STUDENT }

    public enum Status { PENDING_VERIFICATION, ACTIVE, LOCKED, DISABLED }

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(nullable = false)
    private String username;

    private String email;
    private String phone;

    /** When the email was confirmed with a code (FR-ADM-02); emails go only to confirmed addresses. */
    private java.time.OffsetDateTime emailVerifiedAt;

    /** Authenticator-app MFA (BR-36): the encrypted TOTP secret, one waiting to be confirmed, and one-time recovery codes (hashed). */
    private String mfaSecret;
    private String mfaPendingSecret;
    private java.time.OffsetDateTime mfaEnabledAt;
    private String mfaRecoveryCodes;

    /** Set when the personal data of the account was anonymised under the retention policy (BR-38). */
    private java.time.OffsetDateTime anonymisedAt;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int failedLogins;

    private OffsetDateTime lastLoginAt;

    /** Set when the administrator issues a temporary password; the user chooses their own at the next sign-in. */
    @Column(nullable = false)
    private boolean mustChangePassword;

    /** en or sw, as the person chose (BR-40); null until they choose. */
    private String language;

    /** Sessions (tokens) issued before this moment are no longer accepted: disabled, roles changed or password reset. */
    private OffsetDateTime sessionsValidAfter;

    private String jobTitle;
    private String createdByName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    private Set<RoleAssignment> roles = new HashSet<>();

    public void grant(Role role) {
        roles.add(new RoleAssignment(role, "INSTITUTION", null));
    }

    /** Grants a role limited to part of the institution, e.g. HEAD_OF_DEPARTMENT for one department (scope DEPARTMENT). */
    public void grant(Role role, String scopeType, Long scopeId) {
        roles.add(new RoleAssignment(role, scopeType, scopeId));
    }

    public boolean hasRole(Role role) {
        return roles.stream().anyMatch(r -> r.getRole() == role);
    }

    @Getter
    @Setter
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleAssignment {
        @Enumerated(EnumType.STRING)
        @Column(name = "role", nullable = false)
        private Role role;

        @Column(name = "scope_type", nullable = false)
        private String scopeType = "INSTITUTION";

        @Column(name = "scope_id")
        private Long scopeId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RoleAssignment that)) return false;
            return role == that.role && scopeType.equals(that.scopeType);
        }

        @Override
        public int hashCode() {
            return 31 * role.hashCode() + scopeType.hashCode();
        }
    }
}
