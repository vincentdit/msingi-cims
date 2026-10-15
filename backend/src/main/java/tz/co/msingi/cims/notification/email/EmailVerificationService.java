package tz.co.msingi.cims.notification.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.OtpService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verified email addresses (FR-ADM-02). A signed-in applicant, student or staff member gives an address; a six-digit code is emailed to
 * it; the address is saved as verified only when the code comes back. Emails (event messages, letters, scheduled reports) go only to
 * verified addresses.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int TTL_MINUTES = 30, MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final EmailService email;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public record EmailRequest(@NotBlank @Email @Size(max = 120) String email) {
    }

    public record CodeRequest(@NotBlank @Size(max = 10) String code) {
    }

    /** The account's email and whether it is verified; pending = an address waiting for its code. devCode only in development. */
    public record EmailStatus(String email, boolean verified, OffsetDateTime verifiedAt, String pending, String devCode) {
    }

    @Transactional(readOnly = true)
    public EmailStatus status() {
        Long uid = CurrentUser.requireId();
        Map<String, Object> u = jdbc.queryForMap("select email, email_verified_at from user_accounts where id = ?", uid);
        String pending = jdbc.queryForList("""
                select email from email_verifications where user_id = ? and used_at is null and expires_at > now() and attempts < ?
                order by id desc limit 1""", String.class, uid, MAX_ATTEMPTS).stream().findFirst().orElse(null);
        Object raw = u.get("email_verified_at");
        OffsetDateTime at = raw instanceof java.sql.Timestamp ts ? ts.toInstant().atOffset(java.time.ZoneOffset.UTC)
                : raw instanceof OffsetDateTime o ? o : null;
        return new EmailStatus((String) u.get("email"), at != null, at, pending, null);
    }

    @Transactional
    public EmailStatus request(String address) {
        Long uid = CurrentUser.requireId();
        String e = address.trim().toLowerCase(Locale.ROOT);
        Integer taken = jdbc.queryForObject("select count(*) from user_accounts where lower(email) = ? and id <> ?", Integer.class, e, uid);
        if (taken != null && taken > 0) throw new BusinessRuleException("EMAIL_TAKEN", "That email address is used by another account");
        Integer recent = jdbc.queryForObject("select count(*) from email_verifications where user_id = ? and created_at > now() - interval '1 hour'", Integer.class, uid);
        if (recent != null && recent >= 5) throw new BusinessRuleException("TOO_MANY_CODES", "Too many codes requested; try again in an hour");
        jdbc.update("update email_verifications set used_at = now() where user_id = ? and used_at is null", uid);
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("insert into email_verifications (tenant_id, user_id, email, code_hash, expires_at) values (cims_current_tenant(), ?, ?, ?, ?)",
                uid, e, encoder.encode(code), OffsetDateTime.now().plusMinutes(TTL_MINUTES));
        String institution = tenants.findById(TenantContext.requireTenantId()).map(t -> t.getName()).orElse("CIMS");
        String text = "%s is your %s code to confirm this email address. It expires in %d minutes. If you did not ask for it, ignore this email.";
        email.queueSecret(e, null, "Confirm your email address — " + institution, String.format(text, code, institution, TTL_MINUTES),
                String.format(text, "******", institution, TTL_MINUTES));
        audit.record("EMAIL_VERIFICATION_SENT", "UserAccount", uid, Map.of("email", mask(e)));
        EmailStatus s = status();
        return new EmailStatus(s.email(), s.verified(), s.verifiedAt(), s.pending(), otp.exposeInResponse() ? code : null);
    }

    @Transactional(noRollbackFor = BusinessRuleException.class)
    public EmailStatus verify(String code) {
        Long uid = CurrentUser.requireId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, email, code_hash, attempts, expires_at > now() as live from email_verifications
                where user_id = ? and used_at is null order by id desc limit 1""", uid);
        if (rows.isEmpty()) throw new BusinessRuleException("CODE_INVALID", "Ask for a new code first");
        Map<String, Object> r = rows.get(0);
        long id = ((Number) r.get("id")).longValue();
        int attempts = ((Number) r.get("attempts")).intValue();
        if (!Boolean.TRUE.equals(r.get("live")) || attempts >= MAX_ATTEMPTS) {
            jdbc.update("update email_verifications set used_at = now() where id = ?", id);
            throw new BusinessRuleException("CODE_INVALID", "The code has expired; ask for a new one");
        }
        jdbc.update("update email_verifications set attempts = attempts + 1 where id = ?", id);
        if (!encoder.matches(code.trim(), (String) r.get("code_hash"))) {
            throw new BusinessRuleException("CODE_INVALID", "The code is incorrect (" + (MAX_ATTEMPTS - attempts - 1) + " attempt(s) left)");
        }
        String e = (String) r.get("email");
        jdbc.update("update email_verifications set used_at = now() where id = ?", id);
        int n = jdbc.update("update user_accounts set email = ?, email_verified_at = now() where id = ?", e, uid);
        if (n == 0) throw new NotFoundException("Account", uid);
        // keep the applicant and student records in step with the account
        jdbc.update("update applicants set email = ? where user_id = ?", e, uid);
        jdbc.update("update students set email = ? where user_id = ?", e, uid);
        audit.record("EMAIL_VERIFIED", "UserAccount", uid, Map.of("email", mask(e)));
        return status();
    }

    static String mask(String e) {
        return SimulatedEmailGateway.mask(e);
    }
}
