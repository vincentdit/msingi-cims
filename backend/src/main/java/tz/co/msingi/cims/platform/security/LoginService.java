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
    private final tz.co.msingi.cims.platform.security.mfa.MfaService mfa;

    @Transactional(noRollbackFor = {BadCredentialsException.class, LockedException.class, BusinessRuleException.class})
    public TokenService.TokenResponse login(String login, String password, UserAccount.UserType expectedType) {
        UserAccount user = users.findByLogin(login.trim())
                .filter(u -> u.getUserType() == expectedType)
                .orElseThrow(() -> new BadCredentialsException(GENERIC_ERROR));

        if (user.getStatus() == UserAccount.Status.LOCKED) {
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(), Map.of("reason", "account locked"));
            throw new LockedException("Account locked after repeated failed logins. Contact the institution.");
        }
        if (user.getStatus() == UserAccount.Status.DISABLED) {
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(), Map.of("reason", "account disabled"));
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setFailedLogins(user.getFailedLogins() + 1);
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(),
                    Map.of("reason", "wrong password", "failedLogins", user.getFailedLogins()));
            if (user.getFailedLogins() >= props.maxFailedLogins()) {
                user.setStatus(UserAccount.Status.LOCKED);
                audit.recordAs(user.getId(), user.getFullName(), "ACCOUNT_LOCKED", "UserAccount", user.getId(), Map.of("failedLogins", user.getFailedLogins()));
            }
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (user.getStatus() == UserAccount.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleException("ACCOUNT_NOT_VERIFIED", "Verify your phone number with the code sent by SMS before signing in.");
        }
        if (user.getUserType() == UserAccount.UserType.STAFF && tz.co.msingi.cims.platform.security.mfa.MfaPolicy.enabled(user)) {
            // the password is right; the authenticator code comes next (BR-36)
            return TokenService.mfaChallenge(mfa.challenge(user), user.getPhone() != null && !user.getPhone().isBlank());
        }
        users.recordSignIn(user.getId());
        audit.recordAs(user.getId(), user.getFullName(), "LOGIN", "UserAccount", user.getId(), null);
        return tokens.issue(user);
    }
}
