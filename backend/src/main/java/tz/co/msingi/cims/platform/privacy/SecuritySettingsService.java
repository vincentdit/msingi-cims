package tz.co.msingi.cims.platform.privacy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.util.List;
import java.util.Map;

/** The institution's security and privacy settings: MFA policy, idle time-outs per user type (NFR-SEC-02) and retention periods (BR-38). */
@Service
@RequiredArgsConstructor
public class SecuritySettingsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record Settings(@NotBlank @Pattern(regexp = "OPTIONAL|PRIVILEGED|ALL_STAFF") String mfaPolicy,
                           @Min(5) @Max(480) int staffIdleMinutes, @Min(5) @Max(480) int studentIdleMinutes, @Min(5) @Max(480) int applicantIdleMinutes,
                           @Min(6) @Max(120) int retentionApplicantMonths, @Min(3) @Max(120) int retentionMessageMonths, boolean retentionEnabled) {
    }

    public record IdleMinutes(int staff, int student, int applicant) {
    }

    @Transactional(readOnly = true)
    public Settings get() {
        List<Settings> r = jdbc.query("""
                select mfa_policy, staff_idle_minutes, student_idle_minutes, applicant_idle_minutes, retention_applicant_months,
                       retention_message_months, retention_enabled from security_settings""",
                (rs, i) -> new Settings(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getBoolean(7)));
        return r.isEmpty() ? new Settings("OPTIONAL", 30, 60, 60, 24, 24, false) : r.get(0);
    }

    @Transactional
    public Settings save(Settings s) {
        Settings before = get();
        // whoever makes MFA compulsory must already use it, so the institution is not left without an administrator who can sign in
        if (!"OPTIONAL".equals(s.mfaPolicy()) && "OPTIONAL".equals(before.mfaPolicy())) {
            Long me = tz.co.msingi.cims.platform.security.CurrentUser.id().orElse(null);
            Integer on = me == null ? 0 : jdbc.queryForObject("select count(*) from user_accounts where id = ? and mfa_enabled_at is not null", Integer.class, me);
            if (me != null && (on == null || on == 0)) {
                throw new tz.co.msingi.cims.common.exception.BusinessRuleException("MFA_SETUP_FIRST",
                        "Set up two-step sign-in on your own account before making it compulsory.");
            }
        }
        int n = jdbc.update("""
                update security_settings set mfa_policy = ?, staff_idle_minutes = ?, student_idle_minutes = ?, applicant_idle_minutes = ?,
                  retention_applicant_months = ?, retention_message_months = ?, retention_enabled = ?, updated_at = now()""",
                s.mfaPolicy(), s.staffIdleMinutes(), s.studentIdleMinutes(), s.applicantIdleMinutes(), s.retentionApplicantMonths(),
                s.retentionMessageMonths(), s.retentionEnabled());
        if (n == 0) {
            jdbc.update("""
                    insert into security_settings (tenant_id, mfa_policy, staff_idle_minutes, student_idle_minutes, applicant_idle_minutes,
                      retention_applicant_months, retention_message_months, retention_enabled) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?)""",
                    s.mfaPolicy(), s.staffIdleMinutes(), s.studentIdleMinutes(), s.applicantIdleMinutes(), s.retentionApplicantMonths(),
                    s.retentionMessageMonths(), s.retentionEnabled());
        }
        audit.record("SECURITY_SETTINGS_UPDATED", "SecuritySettings", null, Map.of("before", before.toString(), "after", s.toString()));
        return get();
    }

    @Transactional(readOnly = true)
    public IdleMinutes idle() {
        Settings s = get();
        return new IdleMinutes(s.staffIdleMinutes(), s.studentIdleMinutes(), s.applicantIdleMinutes());
    }
}
