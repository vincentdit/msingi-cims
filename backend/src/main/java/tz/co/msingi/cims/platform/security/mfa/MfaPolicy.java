package tz.co.msingi.cims.platform.security.mfa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Who must use MFA under the institution's policy (security settings): everyone, privileged roles, or nobody. */
@Component
@RequiredArgsConstructor
public class MfaPolicy {

    public static final Set<Role> PRIVILEGED = EnumSet.of(Role.INSTITUTION_ADMIN, Role.MANAGEMENT_VIEWER, Role.DEPUTY_PRINCIPAL_ACADEMIC,
            Role.ADMISSIONS_OFFICER, Role.REGISTRAR, Role.EXAMINATIONS_OFFICER, Role.BURSAR, Role.ACCOUNTANT, Role.AUDITOR);

    private final JdbcTemplate jdbc;

    public String policy() {
        List<String> p = jdbc.queryForList("select mfa_policy from security_settings", String.class);
        return p.isEmpty() ? "OPTIONAL" : p.get(0);
    }

    public boolean required(UserAccount u) {
        if (u.getUserType() != UserAccount.UserType.STAFF) return false;
        return switch (policy()) {
            case "ALL_STAFF" -> true;
            case "PRIVILEGED" -> u.getRoles().stream().anyMatch(r -> PRIVILEGED.contains(r.getRole()));
            default -> false;
        };
    }

    public static boolean enabled(UserAccount u) {
        return u.getMfaEnabledAt() != null && u.getMfaSecret() != null;
    }
}
