package tz.co.msingi.cims.platform.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.web.PageResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the audit trail for the Auditor and the Institution Administrator: filtered pages, the filter choices, a summary
 * of recent activity and security events, and CSV export. The trail itself is append-only (database trigger) and limited
 * to the institution by row-level security.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    public static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    public static final int MAX_EXPORT = 100_000;
    private static final int MAX_DAYS = 366;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public record Filter(LocalDate from, LocalDate to, String area, String entity, Long entityId, String action, Long actorId,
                         String q, boolean securityOnly) {
    }

    public record Entry(Long id, OffsetDateTime occurredAt, Long actorUserId, String actorName, String action, String entity,
                        Long entityId, String area, Map<String, Object> details, String ipAddress) {
    }

    public record Count(String key, String label, long count) {
    }

    public record Facets(List<String> areas, List<Count> entities, List<Count> actions, List<Count> actors) {
    }

    public record Day(LocalDate date, long count) {
    }

    public record Summary(LocalDate from, LocalDate to, long actions, long people, long signIns, long failedSignIns, long lockedAccounts,
                          long accountChanges, List<Day> days, List<Count> topActors, List<Count> topAreas) {
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<Entry> search(Filter f, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p);
        long total = jdbc.queryForObject("select count(*) from audit_logs a where " + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Entry> rows = jdbc.query("select a.* from audit_logs a where " + where + " order by a.occurred_at desc, a.id desc limit :limit offset :offset",
                p, (rs, i) -> entry(rs));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public Facets facets() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<Count> entities = jdbc.query("select entity, count(*) c from audit_logs group by entity order by entity", p,
                (rs, i) -> new Count(rs.getString(1), AuditCatalog.areaOf(rs.getString(1)), rs.getLong(2)));
        List<Count> actions = jdbc.query("select action, count(*) c from audit_logs group by action order by action", p,
                (rs, i) -> new Count(rs.getString(1), rs.getString(1), rs.getLong(2)));
        List<Count> actors = jdbc.query("""
                select actor_user_id, max(actor_name) n, count(*) c from audit_logs where actor_user_id is not null
                group by actor_user_id order by max(actor_name)""", p,
                (rs, i) -> new Count(String.valueOf(rs.getLong(1)), rs.getString(2), rs.getLong(3)));
        return new Facets(AuditCatalog.areas(), entities, actions, actors);
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        Filter f = new Filter(from, to, null, null, null, null, null, null, false);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p);
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) actions, count(distinct actor_user_id) people,
                       count(*) filter (where action = 'LOGIN') sign_ins,
                       count(*) filter (where action = 'LOGIN_FAILED') failed,
                       count(*) filter (where action = 'ACCOUNT_LOCKED') locked,
                       count(*) filter (where entity = 'StaffUser') account_changes
                from audit_logs a where\s""" + where, p);
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        for (LocalDate d = f.from(); !d.isAfter(f.to()); d = d.plusDays(1)) byDay.put(d, 0L);
        jdbc.query("select cast(a.occurred_at at time zone 'Africa/Dar_es_Salaam' as date) d, count(*) c from audit_logs a where "
                + where + " group by 1", p, rs -> {
            byDay.put(rs.getObject(1, LocalDate.class), rs.getLong(2));
        });
        List<Count> topActors = jdbc.query("select actor_user_id, max(actor_name), count(*) c from audit_logs a where " + where
                + " and actor_user_id is not null group by actor_user_id order by c desc limit 5", p,
                (rs, i) -> new Count(String.valueOf(rs.getLong(1)), rs.getString(2), rs.getLong(3)));
        Map<String, Long> areas = new LinkedHashMap<>();
        jdbc.query("select entity, count(*) from audit_logs a where " + where + " group by entity", p,
                rs -> { areas.merge(AuditCatalog.areaOf(rs.getString(1)), rs.getLong(2), Long::sum); });
        List<Count> topAreas = areas.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new Count(e.getKey(), e.getKey(), e.getValue())).toList();
        return new Summary(f.from(), f.to(), num(totals, "actions"), num(totals, "people"), num(totals, "sign_ins"), num(totals, "failed"),
                num(totals, "locked"), num(totals, "account_changes"),
                byDay.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue())).toList(), topActors, topAreas);
    }

    /** The filtered trail as CSV (newest first); the export itself is recorded. */
    @Transactional
    public byte[] export(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p);
        long total = jdbc.queryForObject("select count(*) from audit_logs a where " + where, p, Long.class);
        if (total > MAX_EXPORT) {
            throw new BusinessRuleException("EXPORT_TOO_LARGE", "These filters match " + total + " records; narrow them to at most "
                    + MAX_EXPORT + " to export.");
        }
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(Csv.line("When (EAT)", "Who", "User ID", "Action", "Area", "Record", "Record ID", "Details", "From (IP)"));
        jdbc.query("select a.* from audit_logs a where " + where + " order by a.occurred_at desc, a.id desc", p, rs -> {
            Entry e = entry(rs);
            sb.append(Csv.line(e.occurredAt().atZoneSameInstant(ZONE).toLocalDateTime().withNano(0).toString().replace('T', ' '),
                    e.actorName(), e.actorUserId(), e.action(), e.area(), e.entity(), e.entityId(),
                    e.details() == null ? "" : json(e.details()), e.ipAddress()));
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("records", total);
        details.put("from", f.from().toString());
        details.put("to", f.to().toString());
        if (f.area() != null) details.put("area", f.area());
        if (f.entity() != null) details.put("entity", f.entity());
        if (f.action() != null) details.put("action", f.action());
        if (f.actorId() != null) details.put("actorId", f.actorId());
        if (f.q() != null) details.put("search", f.q());
        audit.record("AUDIT_EXPORTED", "AuditTrail", null, details);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    /** Fills in default dates (the last 7 days) and checks the range. */
    public static Filter normalise(Filter f) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate to = f.to() == null ? today : f.to();
        LocalDate from = f.from() == null ? to.minusDays(6) : f.from();
        if (from.isAfter(to)) {
            throw new BusinessRuleException("INVALID_RANGE", "The start date is after the end date.");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new BusinessRuleException("RANGE_TOO_LONG", "Choose a period of at most a year.");
        }
        return new Filter(from, to, blank(f.area()), blank(f.entity()), f.entityId(), blank(f.action()), f.actorId(), blank(f.q()), f.securityOnly());
    }

    private String where(Filter raw, MapSqlParameterSource p) {
        Filter f = normalise(raw);
        List<String> w = new ArrayList<>();
        // a whole record history is not limited by dates
        if (f.entity() == null || f.entityId() == null) {
            w.add("a.occurred_at >= :fromTs and a.occurred_at < :toTs");
            p.addValue("fromTs", f.from().atStartOfDay(ZONE).toOffsetDateTime());
            p.addValue("toTs", f.to().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        }
        if (f.area() != null) {
            if ("Other".equals(f.area())) {
                w.add("a.entity not in (:known)");
                p.addValue("known", AuditCatalog.knownEntities());
            } else {
                List<String> entities = AuditCatalog.entitiesOf(f.area());
                if (entities.isEmpty()) throw new BusinessRuleException("UNKNOWN_AREA", "There is no area called " + f.area() + ".");
                w.add("a.entity in (:areaEntities)");
                p.addValue("areaEntities", entities);
            }
        }
        if (f.entity() != null) {
            w.add("a.entity = :entity");
            p.addValue("entity", f.entity());
        }
        if (f.entityId() != null) {
            w.add("a.entity_id = :entityId");
            p.addValue("entityId", f.entityId());
        }
        if (f.action() != null) {
            w.add("a.action = :action");
            p.addValue("action", f.action());
        }
        if (f.actorId() != null) {
            w.add("a.actor_user_id = :actorId");
            p.addValue("actorId", f.actorId());
        }
        if (f.securityOnly()) {
            w.add("(a.action in (:securityActions) or a.entity = 'StaffUser')");
            p.addValue("securityActions", AuditCatalog.SECURITY_ACTIONS);
        }
        if (f.q() != null) {
            w.add("(a.actor_name ilike :q or a.action ilike :q or a.entity ilike :q or a.ip_address ilike :q or a.details::text ilike :q"
                    + " or cast(a.entity_id as text) = :qExact)");
            p.addValue("q", "%" + f.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            p.addValue("qExact", f.q());
        }
        return String.join(" and ", w.isEmpty() ? List.of("true") : w);
    }

    private Entry entry(ResultSet rs) throws SQLException {
        String entity = rs.getString("entity");
        long actor = rs.getLong("actor_user_id");
        Long actorId = rs.wasNull() ? null : actor;
        long eid = rs.getLong("entity_id");
        Long entityId = rs.wasNull() ? null : eid;
        String details = rs.getString("details");
        return new Entry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class), actorId, rs.getString("actor_name"),
                rs.getString("action"), entity, entityId, AuditCatalog.areaOf(entity), parse(details), rs.getString("ip_address"));
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of("value", json);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static long num(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number n ? n.longValue() : 0L;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
