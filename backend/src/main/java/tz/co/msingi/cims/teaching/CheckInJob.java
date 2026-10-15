package tz.co.msingi.cims.teaching;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Every minute, closes self check-in sessions past their closing time, recording absentees (FR-TCH-03). */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckInJob {

    private final JobLock jobLock;

    private final TenantRepository tenants;
    private final CheckInService service;

    @Scheduled(cron = "${cims.teaching.checkin-close-cron:15 * * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("check-in-close", Duration.ofSeconds(50), Duration.ofSeconds(20), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(new TenantContext.TenantInfo(t.getId(), t.getCode()), () -> {
                    int n = service.closeExpired();
                    if (n > 0) log.info("Closed {} self check-in session(s) for institution {}", n, t.getCode());
                });
            } catch (RuntimeException e) {
                log.error("Closing self check-in failed for institution {}", t.getCode(), e);
            }
        }
    }
}
