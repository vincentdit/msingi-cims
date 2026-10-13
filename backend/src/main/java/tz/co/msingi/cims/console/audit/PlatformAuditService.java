package tz.co.msingi.cims.console.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The platform's own audit trail (append-only): what Msingi staff did, and to which institution. */
@Service
@RequiredArgsConstructor
public class PlatformAuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Entry(Long id, OffsetDateTime occurredAt, String actorName, String action, Long tenantId, String tenantCode,
                        String entity, Long entityId, Map<String, Object> details, String ipAddress) {
    }

    public record EntryPage(List<Entry> content, long totalElements, int page, int size) {
    }

    public void record(String action, Long tenantId, String entity, Long entityId, Map<String, Object> details) {
        recordAs(CurrentUser.platformId().orElse(null), CurrentUser.platformName(), action, tenantId, entity, entityId, details);
    }

    public void recordAs(Long actorId, String actorName, String action, Long tenantId, String entity, Long entityId, Map<String, Object> details) {
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            ip = forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        }
        String d;
        try {
            d = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            d = null;
        }
        jdbc.update("insert into platform_audit_logs (actor_id, actor_name, action, tenant_id, entity, entity_id, details, ip_address)"
                + " values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)", actorId, actorName, action, tenantId, entity, entityId, d, ip);
    }

    @SuppressWarnings("unchecked")
    public EntryPage page(Long tenantId, String action, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) {
            where.append(" and a.tenant_id = ?");
            args.add(tenantId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" and a.action = ?");
            args.add(action.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from platform_audit_logs a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<Entry> rows = jdbc.query("select a.*, t.code as tenant_code from platform_audit_logs a left join tenants t on t.id = a.tenant_id"
                + where + " order by a.occurred_at desc, a.id desc limit ? offset ?", (rs, i) -> {
            Map<String, Object> details = null;
            String raw = rs.getString("details");
            if (raw != null) {
                try {
                    details = json.readValue(raw, Map.class);
                } catch (JsonProcessingException e) {
                    details = Map.of("raw", raw);
                }
            }
            return new Entry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor_name"),
                    rs.getString("action"), (Long) rs.getObject("tenant_id"), rs.getString("tenant_code"), rs.getString("entity"),
                    (Long) rs.getObject("entity_id"), details, rs.getString("ip_address"));
        }, pageArgs.toArray());
        return new EntryPage(rows, total == null ? 0 : total, p, s);
    }

    public List<String> actions() {
        return jdbc.queryForList("select distinct action from platform_audit_logs order by action", String.class);
    }
}
