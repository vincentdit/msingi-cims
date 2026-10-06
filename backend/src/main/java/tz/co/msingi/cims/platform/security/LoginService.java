package tz.co.msingi.cims.platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.time.OffsetDateTime;
import java.util.Map;

/** Password authentication with account lock-out (NFR-SEC-01). */
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final String GENERIC_ERROR = "Invalid login or password";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final CimsSecurityProperties props;
    private final AuditService audit;

    @Transactional(noRollbackFor = {BadCredentialsException.class, LockedException.class, BusinessRuleException.class})
    public TokenService.TokenResponse login(String login, String password, UserAccount.UserType expectedType) {
        UserAccount user = users.findByLogin(login.trim())
                .filter(u -> u.getUserType() == expectedType)
                .orElseThrow(() -> new BadCredentialsException(GENERIC_ERROR));

        if (user.getStatus() == UserAccount.Status.LOCKED) {
            throw new LockedException("Account locked after repeated failed logins. Contact the institution.");
        }
        if (user.getStatus() == UserAccount.Status.DISABLED) {
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setFailedLogins(user.getFailedLogins() + 1);
            if (user.getFailedLogins() >= props.maxFailedLogins()) {
                user.setStatus(UserAccount.Status.LOCKED);
                audit.record("ACCOUNT_LOCKED", "UserAccount", user.getId(), Map.of("failedLogins", user.getFailedLogins()));
            }
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (user.getStatus() == UserAccount.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleException("ACCOUNT_NOT_VERIFIED", "Verify your phone number with the code sent by SMS before signing in.");
        }
        user.setFailedLogins(0);
        user.setLastLoginAt(OffsetDateTime.now());
        audit.recordAs(user.getId(), user.getFullName(), "LOGIN", "UserAccount", user.getId(), null);
        return tokens.issue(user);
    }
}
