package tz.co.msingi.cims.admission.service;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Every five minutes, closes admission windows whose closing date has passed, so the record shows when and why each window closed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionWindowJob {

    private final JobLock jobLock;

    private final TenantRepository tenants;
    private final AdmissionWindowService windows;

    @Scheduled(cron = "${cims.admission.window-close-cron:0 */5 * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("admission-window-close", Duration.ofMinutes(4), Duration.ofSeconds(30), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(new TenantContext.TenantInfo(t.getId(), t.getCode()), () -> {
                    int n = windows.closeExpired();
                    if (n > 0) log.info("Closed {} admission window(s) past their closing date for {}", n, t.getCode());
                });
            } catch (RuntimeException e) {
                log.error("Closing admission windows failed for institution {}", t.getCode(), e);
            }
        }
    }
}
