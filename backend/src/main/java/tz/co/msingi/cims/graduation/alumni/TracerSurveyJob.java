package tz.co.msingi.cims.graduation.alumni;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;

/** Every morning: closes tracer surveys past their closing date and reminds graduates who have not answered (FR-GRD-05). */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracerSurveyJob {

    private final JobLock jobLock;
    private final TenantRepository tenants;
    private final AlumniService service;

    @Scheduled(cron = "${cims.alumni.tracer-cron:0 0 10 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("tracer-surveys", Duration.ofHours(1), Duration.ofMinutes(5), this::runNow);
    }

    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> service.daily());
            } catch (RuntimeException e) {
                log.error("Tracer survey reminders failed for institution {}", t.getCode(), e);
            }
        }
    }
}
