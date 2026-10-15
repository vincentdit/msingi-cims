package tz.co.msingi.cims.integration.settings;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The institution's codes at GePG, HESLB, MUSE and TAMISEMI, and which optional exchanges it uses (FR-INT-03/05/06/07/08).
 * A code left empty falls back to the institution's own code where that is what the outside body uses.
 */
@Service
@RequiredArgsConstructor
public class IntegrationSettingsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Environment env;

    public record Settings(@Size(max = 20) @Pattern(regexp = "^$|^[A-Za-z0-9]+$") String gepgSpCode,
                           @Size(max = 20) @Pattern(regexp = "^$|^[A-Za-z0-9]+$") String gepgSubSpCode,
                           @Size(max = 30) @Pattern(regexp = "^$|^[A-Za-z0-9_-]+$") String gepgSystemId,
                           @Size(max = 20) @Pattern(regexp = "^$|^[0-9]+$") String gepgGfsCode,
                           boolean heslbEnabled, @Size(max = 20) String heslbCode,
                           boolean nidaEnabled,
                           boolean museEnabled, @Size(max = 20) String museVoteCode, @Size(max = 30) String museCashAccount,
                           boolean tamisemiEnabled, @Size(max = 20) String tamisemiCode) {
    }

    /** The settings and, for each outside system, whether CIMS talks to the real one or to the built-in simulator. */
    public record View(Settings settings, Map<String, String> modes, String institutionCode, String updatedByName) {
    }

    @Transactional(readOnly = true)
    public Settings get() {
        List<Settings> r = jdbc.query("""
                select gepg_sp_code, gepg_sub_sp_code, gepg_system_id, gepg_gfs_code, heslb_enabled, heslb_code, nida_enabled, muse_enabled,
                       muse_vote_code, muse_cash_account, tamisemi_enabled, tamisemi_code from integration_settings""",
                (rs, i) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6),
                        rs.getBoolean(7), rs.getBoolean(8), rs.getString(9), rs.getString(10), rs.getBoolean(11), rs.getString(12)));
        return r.isEmpty() ? new Settings(null, null, null, null, false, null, false, false, null, null, false, null) : r.get(0);
    }

    @Transactional(readOnly = true)
    public View view() {
        String by = jdbc.queryForList("select updated_by_name from integration_settings", String.class).stream().findFirst().orElse(null);
        return new View(get(), modes(), institutionCode(), by);
    }

    @Transactional
    public View save(Settings s) {
        Settings before = get();
        Object[] args = {blank(s.gepgSpCode()), blank(s.gepgSubSpCode()), blank(s.gepgSystemId()), blank(s.gepgGfsCode()), s.heslbEnabled(),
                upper(s.heslbCode()), s.nidaEnabled(), s.museEnabled(), blank(s.museVoteCode()), blank(s.museCashAccount()), s.tamisemiEnabled(),
                upper(s.tamisemiCode()), CurrentUser.name()};
        int n = jdbc.update("""
                update integration_settings set gepg_sp_code = ?, gepg_sub_sp_code = ?, gepg_system_id = ?, gepg_gfs_code = ?, heslb_enabled = ?,
                  heslb_code = ?, nida_enabled = ?, muse_enabled = ?, muse_vote_code = ?, muse_cash_account = ?, tamisemi_enabled = ?, tamisemi_code = ?,
                  updated_by_name = ?, updated_at = now()""", args);
        if (n == 0) {
            jdbc.update("""
                    insert into integration_settings (tenant_id, gepg_sp_code, gepg_sub_sp_code, gepg_system_id, gepg_gfs_code, heslb_enabled, heslb_code,
                      nida_enabled, muse_enabled, muse_vote_code, muse_cash_account, tamisemi_enabled, tamisemi_code, updated_by_name)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", args);
        }
        audit.record("INTEGRATION_SETTINGS_UPDATED", "IntegrationSettings", null, Map.of("before", before.toString(), "after", get().toString()));
        return view();
    }

    /** The GePG codes needed to send a bill; refuses with a clear message when they are not set. */
    @Transactional(readOnly = true)
    public Settings requireGepg() {
        Settings s = get();
        if (s.gepgSpCode() == null || s.gepgSubSpCode() == null || s.gepgSystemId() == null) {
            throw new BusinessRuleException("GEPG_NOT_SET_UP",
                    "GePG is not set up for this institution: enter the SP code, sub SP code and system id under Integrations.");
        }
        return s;
    }

    @Transactional(readOnly = true)
    public String heslbCode() {
        Settings s = get();
        if (!s.heslbEnabled()) throw new BusinessRuleException("HESLB_OFF", "The HESLB exchange is switched off for this institution (Integrations).");
        return s.heslbCode() != null ? s.heslbCode() : institutionCode();
    }

    @Transactional(readOnly = true)
    public String tamisemiCode() {
        Settings s = get();
        if (!s.tamisemiEnabled()) throw new BusinessRuleException("TAMISEMI_OFF", "TAMISEMI selections are switched off for this institution (Integrations).");
        return s.tamisemiCode() != null ? s.tamisemiCode() : institutionCode();
    }

    @Transactional(readOnly = true)
    public boolean nidaEnabled() {
        return get().nidaEnabled();
    }

    /** The NACTVET registration number when known (what NACTVET and HESLB know the institution by), otherwise CIMS's code. */
    @Transactional(readOnly = true)
    public String institutionCode() {
        return jdbc.queryForObject("select coalesce(nullif(nactvet_reg_no, ''), code) from tenants where id = cims_current_tenant()", String.class);
    }

    private Map<String, String> modes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("GEPG", mode("cims.gepg.mode"));
        m.put("NACTVET", mode("cims.nactvet.mode"));
        m.put("NACTVET_SUBMISSION", mode("cims.nactvet.submission.mode"));
        m.put("HESLB", mode("cims.heslb.mode"));
        m.put("NIDA", mode("cims.nida.mode"));
        m.put("MUSE", mode("cims.muse.mode"));
        m.put("TAMISEMI", mode("cims.tamisemi.mode"));
        return m;
    }

    private String mode(String key) {
        return "live".equalsIgnoreCase(env.getProperty(key, "simulated")) ? "LIVE" : "SIMULATED";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
