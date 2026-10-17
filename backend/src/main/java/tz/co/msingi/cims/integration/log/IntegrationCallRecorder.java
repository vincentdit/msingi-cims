package tz.co.msingi.cims.integration.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

/**
 * Records one call to an outside system (NECTA, NACTVET, GePG, the SMS gateway) in its own transaction, so a failed call is
 * kept even when the work around it is rolled back. Recording never makes the call itself fail.
 */
@Slf4j
@Component
public class IntegrationCallRecorder {

    public enum ExternalSystem { NECTA, NACTVET, NACTVET_AWARDS, NACTVET_CATALOGUE, NACTVET_SUBMISSION, GEPG, COLLECTION, SMS, HESLB, NIDA, MUSE, TAMISEMI, NHIF }

    public enum Outcome { SUCCESS, FAILURE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IntegrationCallRecorder(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(ExternalSystem system, String operation, boolean inbound, boolean live, Outcome outcome, Long durationMs,
                       String reference, String detail) {
        record(TenantContext.currentTenantIdOrNull(), system, operation, inbound, live, outcome, durationMs, reference, detail);
    }

    public void record(Long tenantId, ExternalSystem system, String operation, boolean inbound, boolean live, Outcome outcome, Long durationMs,
                       String reference, String detail) {
        try {
            Runnable insert = () -> tx.executeWithoutResult(s -> jdbc.update(
                    "insert into integration_calls (tenant_id, system, operation, direction, mode, outcome, duration_ms, reference, detail)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, system.name(), cut(operation, 40), inbound ? "IN" : "OUT", live ? "LIVE" : "SIMULATED", outcome.name(),
                    durationMs == null ? null : (int) Math.min(durationMs, Integer.MAX_VALUE), cut(reference, 120), cut(detail, 500)));
            Long current = TenantContext.currentTenantIdOrNull();
            if (java.util.Objects.equals(current, tenantId)) {
                insert.run();
            } else if (tenantId == null) {
                // a call that belongs to no institution is written as the platform
                TenantContext.TenantInfo saved = TenantContext.current().orElse(null);
                TenantContext.clear();
                try {
                    insert.run();
                } finally {
                    if (saved != null) TenantContext.set(saved);
                }
            } else {
                TenantContext.runAs(new TenantContext.TenantInfo(tenantId, null), insert);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the {} {} call: {}", system, operation, e.getMessage());
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
