package tz.co.msingi.cims.platform.security.mfa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.document.QrCodes;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.OtpService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.SessionGuard;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-factor sign-in for staff (BR-36, FR-ADM-S02, NFR-SEC-01). Staff add an authenticator app (Google or Microsoft Authenticator)
 * by scanning a QR code and confirming one code; they get ten one-time recovery codes. After the password, a staff member with MFA
 * gives the six-digit code (or a recovery code, or a code sent by SMS). The institution's policy says who must use it: everyone,
 * privileged roles (administration, registry, examinations, finance, audit) or nobody; until they set it up those users can only set it up.
 */
@Service
@RequiredArgsConstructor
public class MfaService {


    private static final long CHALLENGE_SECONDS = 300;
    static final String SMS_PURPOSE = "MFA_LOGIN";

    private final UserAccountRepository users;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final OtpService otp;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SessionGuard sessions;
    private final MfaPolicy policyRules;
    private final SecureRandom random = new SecureRandom();

    public record Status(boolean enabled, OffsetDateTime enabledAt, boolean required, String policy, int recoveryCodesLeft) {
    }

    public record Setup(String secret, String otpauthUri, String qrPng) {
    }

    public record Enabled(List<String> recoveryCodes, TokenService.TokenResponse token) {
    }

    // ------------------------------------------------------------------ policy

    public String policy() {
        return policyRules.policy();
    }

    public boolean required(UserAccount u) {
        return policyRules.required(u);
    }

    public boolean enabled(UserAccount u) {
        return MfaPolicy.enabled(u);
    }

    // ------------------------------------------------------------------ sign-in

    /** A signed, short-lived challenge naming the user, given back with the code. Not a token: it opens nothing by itself. */
    public String challenge(UserAccount u) {
        String v = TenantContext.requireTenantId() + "." + u.getId() + "." + (Instant.now().getEpochSecond() + CHALLENGE_SECONDS);
        return v + "." + cipher.sign("mfa:" + v);
    }

    private UserAccount fromChallenge(String challenge) {
        String[] p = challenge == null ? new String[0] : challenge.split("\\.");
        if (p.length != 4 || !cipher.sign("mfa:" + p[0] + "." + p[1] + "." + p[2]).equals(p[3])
                || Long.parseLong(p[2]) < Instant.now().getEpochSecond() || !String.valueOf(TenantContext.requireTenantId()).equals(p[0])) {
            throw new BusinessRuleException("MFA_CHALLENGE_EXPIRED", "The sign-in has expired. Sign in again.");
        }
        UserAccount u = users.findById(Long.parseLong(p[1])).orElseThrow(() -> new BadCredentialsException("Invalid sign-in"));
        if (u.getStatus() != UserAccount.Status.ACTIVE) throw new BadCredentialsException("Invalid sign-in");
        return u;
    }

    /** Second step of sign-in: an authenticator code, a recovery code or the SMS code. Wrong codes count towards the lock-out. */
    @Transactional(noRollbackFor = {BusinessRuleException.class, BadCredentialsException.class})
    public TokenService.TokenResponse completeSignIn(String challenge, String code) {
        UserAccount u = fromChallenge(challenge);
        String c = code == null ? "" : code.trim().replace(" ", "");
        boolean ok = Totp.verify(cipher.decrypt(u.getMfaSecret()), c, Instant.now());
        String how = "authenticator";
        if (!ok && c.matches("[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}")) {
            ok = useRecoveryCode(u, c);
            how = "recovery code";
        }
        if (!ok && c.matches("\\d{4,8}")) {
            try {
                otp.verify(u.getId(), SMS_PURPOSE, c);
                ok = true;
                how = "SMS code";
            } catch (BusinessRuleException ignored) {
                // not a valid SMS code either
            }
        }
        if (!ok) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            audit.recordAs(u.getId(), u.getFullName(), "LOGIN_FAILED", "UserAccount", u.getId(), Map.of("reason", "wrong MFA code"));
            if (u.getFailedLogins() >= 5) {
                u.setStatus(UserAccount.Status.LOCKED);
                audit.recordAs(u.getId(), u.getFullName(), "ACCOUNT_LOCKED", "UserAccount", u.getId(), Map.of("failedLogins", u.getFailedLogins()));
            }
            throw new BusinessRuleException("MFA_CODE_WRONG", "The code is not right. Use the current code from your authenticator app.");
        }
        users.recordSignIn(u.getId());
        audit.recordAs(u.getId(), u.getFullName(), "LOGIN", "UserAccount", u.getId(), Map.of("mfa", how));
        return tokens.issue(u);
    }

    /** Sends a sign-in code by SMS (when the phone is with the person but the authenticator app is not). */
    @Transactional
    public void smsCode(String challenge) {
        UserAccount u = fromChallenge(challenge);
        if (u.getPhone() == null || u.getPhone().isBlank()) throw new BusinessRuleException("NO_PHONE", "There is no phone number on your account");
        otp.issue(u, SMS_PURPOSE, tenants.findById(TenantContext.requireTenantId()).map(t -> t.getName()).orElse("CIMS"));
        audit.recordAs(u.getId(), u.getFullName(), "MFA_SMS_SENT", "UserAccount", u.getId(), null);
    }

    // ------------------------------------------------------------------ the signed-in person's own MFA

    @Transactional(readOnly = true)
    public Status status() {
        UserAccount u = me();
        return new Status(enabled(u), u.getMfaEnabledAt(), required(u), policy(), recovery(u).size());
    }

    @Transactional
    public Setup setup() {
        UserAccount u = me();
        String secret = Totp.newSecret();
        u.setMfaPendingSecret(cipher.encrypt(secret));
        String institution = tenants.findById(TenantContext.requireTenantId()).map(t -> t.getShortName() != null ? t.getShortName() : t.getName()).orElse("CIMS");
        String uri = Totp.uri("CIMS " + institution, u.getUsername(), secret);
        return new Setup(secret, uri, "data:image/png;base64," + Base64.getEncoder().encodeToString(QrCodes.png(uri, 240)));
    }

    @Transactional
    public Enabled enable(String code) {
        UserAccount u = me();
        if (u.getMfaPendingSecret() == null) throw new BusinessRuleException("MFA_NOT_STARTED", "Start the set-up first");
        String secret = cipher.decrypt(u.getMfaPendingSecret());
        if (!Totp.verify(secret, code, Instant.now())) throw new BusinessRuleException("MFA_CODE_WRONG", "The code is not right. Check the time on your phone and try the current code.");
        u.setMfaSecret(u.getMfaPendingSecret());
        u.setMfaPendingSecret(null);
        u.setMfaEnabledAt(OffsetDateTime.now());
        List<String> codes = newRecoveryCodes(u);
        audit.record("MFA_ENABLED", "UserAccount", u.getId(), null);
        return new Enabled(codes, tokens.issue(u));
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(String password) {
        UserAccount u = me();
        if (!encoder.matches(password == null ? "" : password, u.getPasswordHash())) throw new BusinessRuleException("WRONG_PASSWORD", "The password is not right");
        if (!enabled(u)) throw new BusinessRuleException("MFA_NOT_ENABLED", "Set up the authenticator first");
        audit.record("MFA_RECOVERY_CODES_RENEWED", "UserAccount", u.getId(), null);
        return newRecoveryCodes(u);
    }

    @Transactional
    public Status disable(String password) {
        UserAccount u = me();
        if (!encoder.matches(password == null ? "" : password, u.getPasswordHash())) throw new BusinessRuleException("WRONG_PASSWORD", "The password is not right");
        if (required(u)) throw new BusinessRuleException("MFA_REQUIRED", "Your role must use an authenticator app; ask the administrator if you lost your phone");
        clear(u);
        audit.record("MFA_DISABLED", "UserAccount", u.getId(), null);
        return status();
    }

    /** The administrator clears a staff member's MFA (lost phone); they set it up again at their next sign-in, and their sessions end. */
    @Transactional
    public void adminReset(Long userId) {
        UserAccount u = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (u.getId().equals(CurrentUser.requireId())) throw new BusinessRuleException("OWN_ACCOUNT", "Another administrator must reset your MFA");
        clear(u);
        u.setSessionsValidAfter(OffsetDateTime.now());
        sessions.evict(TenantContext.requireTenantId(), u.getId());
        audit.record("MFA_RESET", "UserAccount", u.getId(), Map.of("username", u.getUsername()));
    }

    private void clear(UserAccount u) {
        u.setMfaSecret(null);
        u.setMfaPendingSecret(null);
        u.setMfaEnabledAt(null);
        u.setMfaRecoveryCodes(null);
    }

    private List<String> newRecoveryCodes(UserAccount u) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        List<String> codes = new ArrayList<>(), hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String code = sb.substring(0, 4) + "-" + sb.substring(4);
            codes.add(code);
            hashes.add(encoder.encode(code.replace("-", "")));
        }
        u.setMfaRecoveryCodes(json(hashes));
        return codes;
    }

    private boolean useRecoveryCode(UserAccount u, String code) {
        List<String> hashes = recovery(u);
        String c = code.replace("-", "").toUpperCase();
        for (String h : hashes) {
            if (encoder.matches(c, h)) {
                List<String> left = new ArrayList<>(hashes);
                left.remove(h);
                u.setMfaRecoveryCodes(json(left));
                audit.recordAs(u.getId(), u.getFullName(), "MFA_RECOVERY_CODE_USED", "UserAccount", u.getId(), Map.of("left", left.size()));
                return true;
            }
        }
        return false;
    }

    private List<String> recovery(UserAccount u) {
        if (u.getMfaRecoveryCodes() == null) return List.of();
        try {
            return mapper.readValue(u.getMfaRecoveryCodes(), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String json(List<String> l) {
        try {
            return mapper.writeValueAsString(l);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UserAccount me() {
        return users.findById(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("User", CurrentUser.requireId()));
    }
}
