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
import tz.co.msingi.cims.notification.SmsSender;

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
    private final SmsSender sms;
    private final OtpProperties props;
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
        sms.send(user.getPhone(), code + " is your " + institutionName + " verification code. It expires in "
                + props.ttl().toMinutes() + " minutes. Do not share it.");
        return code;
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
