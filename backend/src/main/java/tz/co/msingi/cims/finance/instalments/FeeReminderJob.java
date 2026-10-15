package tz.co.msingi.cims.finance.instalments;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Every morning: instalment reminders and late-payment penalties for every active institution (FR-FIN-07). */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeeReminderJob {

    private final JobLock jobLock;

    private final TenantRepository tenants;
    private final InstalmentService service;

    @Scheduled(cron = "${cims.finance.reminder-cron:0 30 7 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("fee-reminders", Duration.ofHours(1), Duration.ofMinutes(5), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> service.run(InstalmentService.today()));
            } catch (RuntimeException e) {
                log.error("Fee reminders failed for institution {}", t.getCode(), e);
            }
        }
    }
}
