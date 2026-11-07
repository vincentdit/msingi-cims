package tz.co.msingi.cims.notification.sms;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Each institution's sender name (what phones show as the sender, e.g. BAHARI). TCRA and the networks must approve a name before
 * it is used, through Msingi's SMS provider: the institution asks for it on its Messaging page, Msingi submits it with the
 * institution's documents and records the outcome in the console. Until its name is approved, an institution's messages go out
 * under Msingi's own sender name.
 */
@Service
@RequiredArgsConstructor
public class SenderNameService {

    /** 3 to 11 letters or digits, at least one letter, no spaces (TCRA and the networks refuse others). */
    public static final String SENDER_PATTERN = "^(?=.*[A-Za-z])[A-Za-z0-9]{3,11}$";

    public enum Status { REQUESTED, SUBMITTED, APPROVED, REJECTED, WITHDRAWN }

    public record SenderName(Long id, Long tenantId, String institution, String institutionCode, String senderId, String status, String note,
                             String requestedByName, OffsetDateTime requestedAt, String decidedByName, OffsetDateTime decidedAt) {
    }

    public record Decision(@NotNull Status status, @Size(max = 300) String note) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PlatformAuditService platformAudit;

    // ------------------------------------------------------------------ institution

    /** The institution asks for a sender name (on saving its messaging settings); the earlier request, if different, is withdrawn. */
    @Transactional
    public void request(String senderId) {
        String name = senderId == null ? null : senderId.trim();
        String withdraw = "update sms_sender_names set status = 'WITHDRAWN', decided_at = now(), decided_by_name = ?"
                + " where status in ('REQUESTED', 'SUBMITTED', 'APPROVED')";
        if (name == null || name.isEmpty()) {
            jdbc.update(withdraw, CurrentUser.name());
            return;
        }
        jdbc.update(withdraw + " and sender_id <> ?", CurrentUser.name(), name);
        if (!name.matches(SENDER_PATTERN)) {
            throw new BusinessRuleException("SENDER_NAME_INVALID", "A sender name is 3 to 11 letters or digits, with no spaces, e.g. BAHARI.");
        }
        int changed = jdbc.update("""
                update sms_sender_names set status = 'REQUESTED', note = null, decided_at = null, decided_by_name = null,
                  requested_by_name = ?, requested_at = now()
                where sender_id = ? and status in ('REJECTED', 'WITHDRAWN')""", CurrentUser.name(), name);
        Integer exists = jdbc.queryForObject("select count(*) from sms_sender_names where sender_id = ?", Integer.class, name);
        if (changed == 0 && (exists == null || exists == 0)) {
            jdbc.update("insert into sms_sender_names (tenant_id, sender_id, requested_by_name) values (cims_current_tenant(), ?, ?)", name, CurrentUser.name());
        }
        if (changed > 0 || exists == null || exists == 0) {
            audit.record("SMS_SENDER_NAME_REQUESTED", "SmsSenderName", null, Map.of("senderId", name));
        }
    }

    /** The institution's request for this name (current institution), if any. */
    @Transactional(readOnly = true)
    public Optional<SenderName> current(String senderId) {
        if (senderId == null || senderId.isBlank()) return Optional.empty();
        return list("where s.sender_id = ?", senderId.trim()).stream().findFirst();
    }

    /** The name phones will show for the current institution's messages: its own once approved, otherwise Msingi's. */
    @Transactional(readOnly = true)
    public String effective(String senderId, String defaultSender) {
        return current(senderId).filter(s -> Status.APPROVED.name().equals(s.status())).map(SenderName::senderId).orElse(defaultSender);
    }

    // ------------------------------------------------------------------ Msingi console

    @Transactional(readOnly = true)
    public List<SenderName> all() {
        return list("");
    }

    /** Msingi records how the request is going with the provider: submitted, approved or rejected (with why). */
    @Transactional
    public SenderName decide(Long id, Decision d) {
        SenderName s = list("where s.id = ?", id).stream().findFirst().orElseThrow(() -> new NotFoundException("No such sender name request"));
        if (Status.WITHDRAWN.name().equals(s.status())) {
            throw new BusinessRuleException("SENDER_NAME_WITHDRAWN", "The institution has since asked for a different name; this request is withdrawn.");
        }
        if (d.status() == Status.REQUESTED || d.status() == Status.WITHDRAWN) {
            throw new BusinessRuleException("SENDER_NAME_STATUS", "Mark the request submitted, approved or rejected.");
        }
        if (d.status() == Status.REJECTED && (d.note() == null || d.note().isBlank())) {
            throw new BusinessRuleException("SENDER_NAME_REASON", "Say why the name was rejected, so the institution can choose another.");
        }
        jdbc.update("update sms_sender_names set status = ?, note = ?, decided_by_name = ?, decided_at = now() where id = ?",
                d.status().name(), d.note() == null || d.note().isBlank() ? null : d.note().trim(), CurrentUser.platformName(), id);
        platformAudit.record("SMS_SENDER_NAME_" + d.status().name(), s.tenantId(), "SmsSenderName", id, Map.of("senderId", s.senderId()));
        return list("where s.id = ?", id).get(0);
    }

    private List<SenderName> list(String where, Object... args) {
        return jdbc.query("""
                select s.*, t.name as institution, t.code as institution_code
                from sms_sender_names s join tenants t on t.id = s.tenant_id
                """ + where + " order by case s.status when 'REQUESTED' then 0 when 'SUBMITTED' then 1 when 'REJECTED' then 2"
                        + " when 'APPROVED' then 3 else 4 end, s.requested_at desc",
                (rs, i) -> new SenderName(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("institution"), rs.getString("institution_code"),
                        rs.getString("sender_id"), rs.getString("status"), rs.getString("note"), rs.getString("requested_by_name"),
                        rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by_name"),
                        rs.getObject("decided_at", OffsetDateTime.class)), args);
    }
}
