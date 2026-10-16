package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Every morning: application-fee reminders for every active institution (BP-02). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationFeeReminderJob {

    private final JobLock jobLock;
    private final TenantRepository tenants;
    private final ApplicationFeeReminders reminders;

    @Scheduled(cron = "${cims.admission.fee-reminder-cron:0 0 9 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("application-fee-reminders", Duration.ofHours(1), Duration.ofMinutes(5), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> reminders.run(OffsetDateTime.now()));
            } catch (RuntimeException e) {
                log.error("Application-fee reminders failed for institution {}", t.getCode(), e);
            }
        }
    }
}
