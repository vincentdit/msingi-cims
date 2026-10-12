package tz.co.msingi.cims.admission.service;

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

    private final TenantRepository tenants;
    private final AdmissionWindowService windows;

    @Scheduled(cron = "${cims.admission.window-close-cron:0 */5 * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
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
