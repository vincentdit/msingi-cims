package tz.co.msingi.cims.teaching;

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

    private final TenantRepository tenants;
    private final CheckInService service;

    @Scheduled(cron = "${cims.teaching.checkin-close-cron:15 * * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
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
