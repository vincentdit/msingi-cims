package tz.co.msingi.cims.integration.log;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder.ExternalSystem;
import tz.co.msingi.cims.integration.nactvet.NactvetAwardClient;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway;
import tz.co.msingi.cims.integration.necta.NectaClient;
import tz.co.msingi.cims.notification.SmsGateway;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the integration log: health per outside system over a period, and the calls themselves. The platform passes no
 * institution (all of them); an institution's auditor passes its own, and row-level security limits it as well.
 */
@Service
@RequiredArgsConstructor
public class IntegrationLogQuery {

    /** The name of every outside system as people read it; each one has a name (a missing one showed as a blank row). */
    public static final Map<ExternalSystem, String> LABELS = Map.ofEntries(
            Map.entry(ExternalSystem.NECTA, "NECTA results"),
            Map.entry(ExternalSystem.NACTVET, "NACTVET admission verification"),
            Map.entry(ExternalSystem.NACTVET_AWARDS, "NACTVET award verification"),
            Map.entry(ExternalSystem.NACTVET_CATALOGUE, "NACTVET institution catalogue"),
            Map.entry(ExternalSystem.NACTVET_SUBMISSION, "NACTVET registration and results"),
            Map.entry(ExternalSystem.GEPG, "GePG payments"),
            Map.entry(ExternalSystem.COLLECTION, "Mobile money and bank collection"),
            Map.entry(ExternalSystem.SMS, "SMS gateway"),
            Map.entry(ExternalSystem.HESLB, "HESLB student loans"),
            Map.entry(ExternalSystem.NIDA, "NIDA national ID check"),
            Map.entry(ExternalSystem.MUSE, "MUSE government accounting"),
            Map.entry(ExternalSystem.TAMISEMI, "TAMISEMI selections"),
            Map.entry(ExternalSystem.NHIF, "NHIF health insurance"));

    private final JdbcTemplate jdbc;
    private final ApplicationContext context;
    private final GepgProperties gepg;

    public record SystemHealth(String system, String label, String mode, String status, long calls, long failures, Double failureRate,
                               Long averageMs, OffsetDateTime lastSuccessAt, OffsetDateTime lastFailureAt, String lastError) {
    }

    public record Call(Long id, OffsetDateTime occurredAt, Long tenantId, String tenantCode, String system, String operation,
                       String direction, String mode, String outcome, Integer durationMs, String reference, String detail) {
    }

    public record CallPage(List<Call> content, long totalElements, int page, int size) {
    }

    public record Filter(Long tenantId, String system, String outcome, OffsetDateTime from, OffsetDateTime to) {
    }

    /**
     * Health of each outside system over the last {@code hours}: DOWN when the most recent calls all failed, DEGRADED when more
     * than a tenth failed, OK otherwise, IDLE without calls.
     */
    public List<SystemHealth> health(Long tenantId, int hours) {
        int h = Math.min(Math.max(hours, 1), 24 * 90);
        List<Object> args = new ArrayList<>();
        String tenant = "";
        if (tenantId != null) {
            tenant = " and tenant_id = ?";
            args.add(tenantId);
        }
        args.add(h);
        Map<String, SystemHealth> out = new LinkedHashMap<>();
        for (ExternalSystem s : ExternalSystem.values()) {
            out.put(s.name(), new SystemHealth(s.name(), LABELS.get(s), mode(s), "IDLE", 0, 0, null, null, null, null, null));
        }
        jdbc.query("""
                select system, count(*) as calls, count(*) filter (where outcome = 'FAILURE') as failures,
                       round(avg(duration_ms)) as avg_ms,
                       max(occurred_at) filter (where outcome = 'SUCCESS') as last_ok,
                       max(occurred_at) filter (where outcome = 'FAILURE') as last_fail
                from integration_calls where 1=1""" + tenant + """
                 and occurred_at >= now() - make_interval(hours => ?)
                group by system""", rs -> {
            String sys = rs.getString("system");
            SystemHealth base = out.get(sys);
            if (base == null) return;
            long calls = rs.getLong("calls"), failures = rs.getLong("failures");
            OffsetDateTime ok = rs.getObject("last_ok", OffsetDateTime.class), fail = rs.getObject("last_fail", OffsetDateTime.class);
            String status = failures == 0 ? "OK" : (ok == null || (fail != null && fail.isAfter(ok) && recentAllFailed(tenantId, sys))) ? "DOWN"
                    : failures * 10 > calls ? "DEGRADED" : "OK";
            Object avg = rs.getObject("avg_ms");
            out.put(sys, new SystemHealth(sys, base.label(), base.mode(), status, calls, failures,
                    calls == 0 ? null : Math.round(failures * 1000.0 / calls) / 10.0, avg == null ? null : ((Number) avg).longValue(), ok, fail,
                    fail == null ? null : lastError(tenantId, sys)));
        }, args.toArray());
        return new ArrayList<>(out.values());
    }

    public CallPage calls(Filter f, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (f.tenantId() != null) {
            where.append(" and c.tenant_id = ?");
            args.add(f.tenantId());
        }
        if (f.system() != null && !f.system().isBlank()) {
            where.append(" and c.system = ?");
            args.add(f.system().trim().toUpperCase());
        }
        if (f.outcome() != null && !f.outcome().isBlank()) {
            where.append(" and c.outcome = ?");
            args.add(f.outcome().trim().toUpperCase());
        }
        if (f.from() != null) {
            where.append(" and c.occurred_at >= ?");
            args.add(f.from());
        }
        if (f.to() != null) {
            where.append(" and c.occurred_at < ?");
            args.add(f.to());
        }
        Long total = jdbc.queryForObject("select count(*) from integration_calls c" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<Call> rows = jdbc.query("select c.*, t.code as tenant_code from integration_calls c left join tenants t on t.id = c.tenant_id"
                + where + " order by c.occurred_at desc, c.id desc limit ? offset ?", (rs, i) -> new Call(rs.getLong("id"),
                rs.getObject("occurred_at", OffsetDateTime.class), (Long) rs.getObject("tenant_id"), rs.getString("tenant_code"),
                rs.getString("system"), rs.getString("operation"), rs.getString("direction"), rs.getString("mode"), rs.getString("outcome"),
                (Integer) rs.getObject("duration_ms"), rs.getString("reference"), rs.getString("detail")), pageArgs.toArray());
        return new CallPage(rows, total == null ? 0 : total, p, s);
    }

    /** Failed calls per institution over the last {@code hours} (platform view). */
    public Map<Long, Long> failuresByTenant(int hours) {
        Map<Long, Long> out = new LinkedHashMap<>();
        jdbc.query("select tenant_id, count(*) as n from integration_calls where outcome = 'FAILURE' and tenant_id is not null"
                + " and occurred_at >= now() - make_interval(hours => ?) group by tenant_id", rs -> {
            out.put(rs.getLong("tenant_id"), rs.getLong("n"));
        }, hours);
        return out;
    }

    private boolean recentAllFailed(Long tenantId, String system) {
        List<Object> args = new ArrayList<>(List.of(system));
        String tenant = "";
        if (tenantId != null) {
            tenant = " and tenant_id = ?";
            args.add(tenantId);
        }
        List<String> last = jdbc.queryForList("select outcome from integration_calls where system = ?" + tenant
                + " order by occurred_at desc, id desc limit 3", String.class, args.toArray());
        return !last.isEmpty() && last.stream().allMatch("FAILURE"::equals);
    }

    private String lastError(Long tenantId, String system) {
        List<Object> args = new ArrayList<>(List.of(system));
        String tenant = "";
        if (tenantId != null) {
            tenant = " and tenant_id = ?";
            args.add(tenantId);
        }
        List<String> rows = jdbc.queryForList("select detail from integration_calls where system = ? and outcome = 'FAILURE'" + tenant
                + " order by occurred_at desc, id desc limit 1", String.class, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** LIVE or SIMULATED, from the adapter in use. */
    private String mode(ExternalSystem s) {
        Class<?> port = switch (s) {
            case NECTA -> NectaClient.class;
            case NACTVET -> NactvetGateway.class;
            case NACTVET_AWARDS -> NactvetAwardClient.class;
            case NACTVET_CATALOGUE -> tz.co.msingi.cims.integration.nactvet.NactvetCatalogueClient.class;
            case GEPG -> null;
            case COLLECTION -> tz.co.msingi.cims.finance.collection.CollectionGateway.class;
            case SMS -> SmsGateway.class;
            case NACTVET_SUBMISSION -> tz.co.msingi.cims.integration.nactvet.NactvetSubmissionClient.class;
            case HESLB -> tz.co.msingi.cims.integration.heslb.HeslbClient.class;
            case NIDA -> tz.co.msingi.cims.integration.nida.NidaClient.class;
            case MUSE -> tz.co.msingi.cims.integration.muse.MuseClient.class;
            case TAMISEMI -> tz.co.msingi.cims.integration.tamisemi.TamisemiClient.class;
            case NHIF -> tz.co.msingi.cims.integration.nhif.NhifClient.class;
        };
        if (port == null) return gepg.isSimulated() ? "SIMULATED" : "LIVE";
        try {
            Object bean = context.getBean(port);
            Class<?> type = org.springframework.aop.support.AopUtils.getTargetClass(bean);
            return type.getSimpleName().startsWith("Simulated") ? "SIMULATED" : "LIVE";
        } catch (RuntimeException e) {
            return "NOT_CONFIGURED";
        }
    }
}
