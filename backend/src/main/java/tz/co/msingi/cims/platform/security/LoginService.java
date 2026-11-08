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
    /** The hash of a random string nobody knows, checked against when the login does not exist. */
    private volatile String dummyHash;

    /** What a locked-out person is told: how long to wait, or whom to ask. */
    public static String lockedMessage(UserAccount u) {
        if (u.getLockedUntil() == null) return "Account locked after repeated failed sign-ins. Ask the institution's administrator to unlock it.";
        long minutes = Math.max(1, java.time.Duration.between(java.time.OffsetDateTime.now(), u.getLockedUntil()).toSeconds() / 60 + 1);
        return "Too many failed sign-ins. Try again in " + minutes + (minutes == 1 ? " minute" : " minutes") + ", or use Forgot password.";
    }

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
                .orElse(null);
        if (user == null) {
            if (dummyHash == null) dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
            passwordEncoder.matches(password, dummyHash);   // as slow as a real check, so the answer does not tell which logins exist
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (user.lockOutEnded()) {
            audit.recordAs(user.getId(), user.getFullName(), "ACCOUNT_UNLOCKED", "UserAccount", user.getId(), Map.of("reason", "lock-out time over"));
        }
        if (user.getStatus() == UserAccount.Status.LOCKED) {
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(), Map.of("reason", "account locked"));
            throw new LockedException(lockedMessage(user));
        }
        if (user.getStatus() == UserAccount.Status.DISABLED) {
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(), Map.of("reason", "account disabled"));
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            boolean locked = user.failedSignIn(props.maxFailedLogins(), props.lockMinutes());
            audit.recordAs(user.getId(), user.getFullName(), "LOGIN_FAILED", "UserAccount", user.getId(),
                    Map.of("reason", "wrong password", "failedLogins", user.getFailedLogins()));
            if (locked) {
                audit.recordAs(user.getId(), user.getFullName(), "ACCOUNT_LOCKED", "UserAccount", user.getId(), Map.of("failedLogins", user.getFailedLogins(),
                        "until", user.getLockedUntil() == null ? "unlocked by an administrator" : user.getLockedUntil().toString()));
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
