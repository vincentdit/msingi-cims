package tz.co.msingi.cims.platform.privacy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Applies each institution's retention periods once a month, where the administrator has switched it on. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final TenantRepository tenants;
    private final SecuritySettingsService settings;
    private final PrivacyService privacy;

    @Scheduled(cron = "${cims.privacy.retention-cron:0 0 2 1 * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> {
                    if (settings.get().retentionEnabled()) privacy.runRetention("Retention schedule");
                });
            } catch (RuntimeException e) {
                log.error("Retention failed for institution {}", t.getCode(), e);
            }
        }
    }
}
