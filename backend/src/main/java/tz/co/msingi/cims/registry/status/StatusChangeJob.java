package tz.co.msingi.cims.registry.status;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Every morning, applies approved student status changes whose effective date has come (BP-14). */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusChangeJob {

    private final TenantRepository tenants;
    private final StatusChangeService service;

    @Scheduled(cron = "${cims.registry.status-cron:0 5 6 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(new TenantContext.TenantInfo(t.getId(), t.getCode()), () -> {
                    int n = service.applyDue();
                    if (n > 0) log.info("Applied {} status change(s) for institution {}", n, t.getCode());
                });
            } catch (RuntimeException e) {
                log.error("Applying status changes failed for institution {}", t.getCode(), e);
            }
        }
    }
}
