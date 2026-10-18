package tz.co.msingi.cims.console.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.billing.BillingModels.Contract;
import tz.co.msingi.cims.console.institutions.InstitutionService;
import tz.co.msingi.cims.platform.jobs.JobLock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every morning: prepares the invoices that have fallen due under each contract in force (the licence at the start of each
 * contract year, usage at the end of each cycle), issues them when the contract says so, and reminds institutions of unpaid
 * invoices — 3 days before the due date, then 1, 14 and 30 days after it. When an invoice is unpaid past the contract's
 * suspension limit, Msingi staff are told, or the institution is suspended if its contract allows it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MsingiBillingJob {

    private final JobLock jobLock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MsingiBillingService billing;
    private final InstitutionService institutions;

    @Scheduled(cron = "${cims.billing.cron:0 15 6 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("msingi-billing", Duration.ofMinutes(50), Duration.ofMinutes(5), this::runNow);
    }

    /** One run; returns how many invoices were prepared and issued and how many reminders were sent. */
    public Map<String, Integer> runNow() {
        return runFor(MsingiBillingService.today());
    }

    Map<String, Integer> runFor(LocalDate today) {
        int prepared = 0, issued = 0, reminded = 0, suspended = 0;
        List<Long> tenants = jdbc.queryForList("""
                select c.tenant_id from billing_contracts c join tenants t on t.id = c.tenant_id where c.status = 'ACTIVE' and c.start_date <= ?""",
                Long.class, java.sql.Date.valueOf(today));
        for (Long tenantId : tenants) {
            try {
                int[] r = tx.execute(s -> prepareDue(tenantId, today));
                prepared += r[0];
                issued += r[1];
            } catch (RuntimeException e) {
                log.error("Msingi billing failed for institution {}", tenantId, e);
            }
        }
        List<Map<String, Object>> open = jdbc.queryForList("""
                select i.id, i.tenant_id, i.due_date, i.reminder_level, c.auto_suspend, c.suspend_after_days, t.status tenant_status
                from billing_invoices i join tenants t on t.id = i.tenant_id
                left join billing_contracts c on c.tenant_id = i.tenant_id and c.status = 'ACTIVE'
                where i.status in ('ISSUED', 'PARTLY_PAID') and i.due_date is not null""");
        for (Map<String, Object> o : open) {
            Long id = ((Number) o.get("id")).longValue();
            long late = ChronoUnit.DAYS.between(((java.sql.Date) o.get("due_date")).toLocalDate(), today);
            int suspendAfter = o.get("suspend_after_days") == null ? 60 : ((Number) o.get("suspend_after_days")).intValue();
            int level = late >= suspendAfter ? 5 : late >= 30 ? 4 : late >= 14 ? 3 : late >= 1 ? 2 : late >= -3 ? 1 : 0;
            int done = ((Number) o.get("reminder_level")).intValue();
            if (level <= done) continue;
            Long tenantId = ((Number) o.get("tenant_id")).longValue();
            boolean suspend = level == 5 && Boolean.TRUE.equals(o.get("auto_suspend")) && "ACTIVE".equals(o.get("tenant_status"));
            try {
                String kind = level == 1 ? "DUE_SOON" : level == 5 ? (suspend ? "SUSPENDED" : "SUSPENSION_DUE") : "OVERDUE";
                tx.executeWithoutResult(s -> billing.sendReminder(id, level, kind));
                reminded++;
                if (level == 5) {
                    String no = jdbc.queryForObject("select invoice_no from billing_invoices where id = ?", String.class, id);
                    String name = jdbc.queryForObject("select name from tenants where id = ?", String.class, tenantId);
                    if (suspend) {
                        institutions.suspend(tenantId, "Msingi invoice " + no + " unpaid " + late + " days after its due date");
                        suspended++;
                    }
                    tx.executeWithoutResult(s -> billing.toMsingi(tenantId, (suspend ? "Suspended for non-payment: " : "Suspension due: ") + name,
                            name + " has not paid invoice " + no + ", " + late + " days after its due date."
                                    + (suspend ? " The institution has been suspended as its contract allows; reactivate it under Institutions once paid."
                                    : " Its contract does not suspend automatically: decide under Institutions whether to suspend it.")));
                }
            } catch (RuntimeException e) {
                log.error("Msingi billing reminder failed for invoice {}", id, e);
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("prepared", prepared);
        out.put("issued", issued);
        out.put("reminders", reminded);
        out.put("suspended", suspended);
        return out;
    }

    /** The licence years and usage cycles that have started (licence) or ended (usage) and were not invoiced yet. */
    private int[] prepareDue(Long tenantId, LocalDate today) {
        int prepared = 0, issued = 0;
        for (int guard = 0; guard < 24; guard++) {
            Contract c = billing.active(tenantId);
            LocalDate from = billing.nextLicenceYear(c);
            if (from.isAfter(today) || (c.endDate() != null && from.isAfter(c.endDate()))) break;
            if (c.annualFee().signum() == 0 && (c.setupBilled() || c.setupFee().signum() == 0)) break;
            Long id = billing.licenceInvoice(c, from, billing.yearEnd(c, from), true);
            prepared++;
            issued += finish(c, id);
        }
        for (int guard = 0; guard < 60; guard++) {
            Contract c = billing.active(tenantId);
            if (c.studentFee().signum() == 0 && c.applicationFee().signum() == 0 && c.applicationPercent().signum() == 0) break;
            LocalDate[] cycle = MsingiBillingService.nextCycle(c);
            if (!cycle[1].isBefore(today) || cycle[1].isBefore(cycle[0])) break;
            Long id = billing.usageInvoice(c, cycle[0], cycle[1], true);
            prepared++;
            issued += finish(c, id);
        }
        return new int[]{prepared, issued};
    }

    /** Issues a generated invoice when the contract says so; a usage invoice with nothing to charge is discarded. */
    private int finish(Contract c, Long id) {
        BigDecimal total = jdbc.queryForObject("select total from billing_invoices where id = ?", BigDecimal.class, id);
        if (total.signum() == 0) {
            jdbc.update("update billing_invoices set status = 'CANCELLED', cancelled_reason = 'Nothing to charge for the period' where id = ?", id);
            return 0;
        }
        if (c.autoIssue()) {
            billing.doIssue(id, "CIMS billing");
            return 1;
        }
        return 0;
    }
}
