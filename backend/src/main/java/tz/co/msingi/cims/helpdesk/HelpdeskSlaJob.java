package tz.co.msingi.cims.helpdesk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.helpdesk.support.SupportService;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;
import java.util.List;

/**
 * Every ten minutes: escalates helpdesk tickets that missed a target (no first reply in time → the office and assignee are told;
 * not resolved in time → the administrators), closes tickets resolved more than seven days ago, and does the same for Msingi
 * support tickets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelpdeskSlaJob {

    private final JobLock jobLock;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HelpdeskService helpdesk;
    private final HelpdeskNotifier notifier;
    private final SupportService support;
    private final tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant assistant;

    @Scheduled(cron = "${cims.helpdesk.sla-cron:0 */10 * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("helpdesk-sla", Duration.ofMinutes(9), Duration.ofMinutes(1), this::runNow);
    }

    /** One run (tests call it directly). */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> runForInstitution()));
            } catch (RuntimeException e) {
                log.error("Helpdesk targets failed for institution {}", t.getCode(), e);
            }
        }
        try {
            support.runTargets();
        } catch (RuntimeException e) {
            log.error("Msingi support targets failed", e);
        }
    }

    void runForInstitution() {
        // level 1: no first reply in time
        List<Long> late = jdbc.queryForList("""
                select id from helpdesk_tickets where status in ('OPEN', 'IN_PROGRESS', 'WAITING') and escalation_level < 1
                  and first_response_at is null and response_due_at < now()""", Long.class);
        for (Long id : late) escalate(id, 1, "Escalated: no reply within the time set; the office has been reminded");
        // level 2: not resolved in time (the clock stops while waiting for the requester)
        List<Long> overdue = jdbc.queryForList("""
                select id from helpdesk_tickets where status in ('OPEN', 'IN_PROGRESS') and escalation_level < 2 and resolution_due_at < now()""", Long.class);
        for (Long id : overdue) escalate(id, 2, "Escalated to the administrators: not resolved within the time set");
        // resolved and not reopened for 7 days: closed
        List<Long> done = jdbc.queryForList("""
                select id from helpdesk_tickets where status = 'RESOLVED' and resolved_at < now() - make_interval(days => ?)""",
                Long.class, HelpdeskService.REOPEN_DAYS);
        for (Long id : done) {
            jdbc.update("update helpdesk_tickets set status = 'CLOSED', closed_at = now(), updated_at = now() where id = ?", id);
            helpdesk.addMessage(id, "SYSTEM", null, "CIMS", "Closed automatically " + HelpdeskService.REOPEN_DAYS + " days after it was resolved", false);
            assistant.learn(id, null);   // not reopened: the reply is learned, not as confirmed
        }
    }

    private void escalate(Long id, int level, String note) {
        jdbc.update("update helpdesk_tickets set escalation_level = ?, updated_at = now() where id = ?", level, id);
        helpdesk.addMessage(id, "SYSTEM", null, "CIMS", note, true);
        HelpdeskService.Ticket t = helpdesk.load(id);
        String category = jdbc.queryForObject("select name from helpdesk_categories where id = ?", String.class, t.categoryId);
        notifier.escalated(t.facts(category), level);
    }
}
