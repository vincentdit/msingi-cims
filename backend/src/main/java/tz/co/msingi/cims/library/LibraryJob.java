package tz.co.msingi.cims.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;

/** Every morning: library items due tomorrow and items that have just become overdue (FR-LIB-04). */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryJob {

    private final JobLock jobLock;
    private final TenantRepository tenants;
    private final LibraryService service;

    @Scheduled(cron = "${cims.library.reminder-cron:0 15 8 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("library-reminders", Duration.ofHours(1), Duration.ofMinutes(5), this::runNow);
    }

    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> service.remind());
            } catch (RuntimeException e) {
                log.error("Library reminders failed for institution {}", t.getCode(), e);
            }
        }
    }
}
