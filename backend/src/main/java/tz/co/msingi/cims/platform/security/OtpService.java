package tz.co.msingi.cims.platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.notification.Messaging;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Issues and verifies SMS one-time codes. Codes are hashed, expire, and allow limited attempts. */
@Service
@RequiredArgsConstructor
public class OtpService {

    public static final String PURPOSE_PHONE_VERIFICATION = "PHONE_VERIFICATION";
    public static final String PURPOSE_PASSWORD_RESET = "PASSWORD_RESET";

    private final OtpCodeRepository repository;
    private final PasswordEncoder encoder;
    private final Messaging messaging;
    private final OtpProperties props;
    private final org.springframework.beans.factory.ObjectProvider<tz.co.msingi.cims.notification.email.EmailService> emails;
    private final SecureRandom random = new SecureRandom();

    /** Creates a new code (invalidating earlier ones), sends it by SMS and returns it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(UserAccount user, String purpose, String institutionName) {
        repository.invalidate(user.getId(), purpose, OffsetDateTime.now());
        String code = String.format("%0" + props.length() + "d", random.nextInt((int) Math.pow(10, props.length())));
        OtpCode otp = new OtpCode();
        otp.setUserId(user.getId());
        otp.setPurpose(purpose);
        otp.setCodeHash(encoder.encode(code));
        otp.setExpiresAt(OffsetDateTime.now().plus(props.ttl()));
        repository.save(otp);
        messaging.securityCode(user.getPhone(), code, java.util.Map.of("institution", institutionName, "minutes", props.ttl().toMinutes()));
        emailCopy(user, purpose, code, institutionName);
        return code;
    }

    /**
     * The same code by email, for an applicant who gave an email address: SMS can be slow or not arrive, and the person may have
     * mistyped the number. Only for confirming the account and resetting the password, never for the second sign-in factor.
     */
    private void emailCopy(UserAccount user, String purpose, String code, String institutionName) {
        if (user.getEmail() == null || user.getEmail().isBlank()) return;
        if (!PURPOSE_PHONE_VERIFICATION.equals(purpose) && !PURPOSE_PASSWORD_RESET.equals(purpose)) return;
        tz.co.msingi.cims.notification.email.EmailService email = emails.getIfAvailable();
        if (email == null || tz.co.msingi.cims.platform.tenancy.TenantContext.current().isEmpty()) return;
        boolean reset = PURPOSE_PASSWORD_RESET.equals(purpose);
        String subject = (reset ? "Your password reset code — " : "Your verification code — ") + institutionName;
        String text = "%s is your " + institutionName + (reset ? " code to reset your password." : " code to confirm your applicant account.")
                + " It expires in " + props.ttl().toMinutes() + " minutes. Do not share it with anyone. If you did not ask for it, ignore this email.";
        try {
            email.queueSecret(user.getEmail(), user.getFullName(), subject, String.format(text, code), String.format(text, "******"));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(OtpService.class).warn("Could not queue the code by email: {}", e.getMessage());
        }
    }

    /** Verifies and consumes a code. Throws a business error if it is wrong, expired or used up. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessRuleException.class)
    public void verify(Long userId, String purpose, String code) {
        OtpCode otp = repository.findLatestActive(userId, purpose)
                .orElseThrow(() -> new BusinessRuleException("OTP_INVALID", "The code is invalid or has expired. Request a new one."));
        if (otp.getExpiresAt().isBefore(OffsetDateTime.now()) || otp.getAttempts() >= props.maxAttempts()) {
            otp.setConsumedAt(OffsetDateTime.now());
            throw new BusinessRuleException("OTP_INVALID", "The code is invalid or has expired. Request a new one.");
        }
        otp.setAttempts(otp.getAttempts() + 1);
        if (!encoder.matches(code == null ? "" : code.trim(), otp.getCodeHash())) {
            throw new BusinessRuleException("OTP_INVALID", "The code is incorrect.");
        }
        otp.setConsumedAt(OffsetDateTime.now());
    }

    public boolean exposeInResponse() {
        return props.exposeInResponse();
    }

    public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {
        @Query("select o from OtpCode o where o.userId = :userId and o.purpose = :purpose and o.consumedAt is null order by o.id desc limit 1")
        Optional<OtpCode> findLatestActive(Long userId, String purpose);

        @Modifying
        @Query("update OtpCode o set o.consumedAt = :now where o.userId = :userId and o.purpose = :purpose and o.consumedAt is null")
        void invalidate(Long userId, String purpose, OffsetDateTime now);
    }

    @ConfigurationProperties(prefix = "cims.otp")
    public record OtpProperties(int length, Duration ttl, int maxAttempts, boolean exposeInResponse) {
        public OtpProperties {
            if (length < 4 || length > 8) length = 6;
            if (ttl == null) ttl = Duration.ofMinutes(10);
            if (maxAttempts <= 0) maxAttempts = 5;
        }
    }
}
