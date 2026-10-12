package tz.co.msingi.cims.hostel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Every morning, releases hostel allocations left unpaid after their payment period and offers the beds to the waiting list (FR-HST-04). */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostelReleaseJob {

    private final TenantRepository tenants;
    private final AllocationService allocations;

    @Scheduled(cron = "${cims.hostel.release-cron:0 15 6 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(new TenantContext.TenantInfo(t.getId(), t.getCode()), allocations::releaseUnpaid);
            } catch (RuntimeException e) {
                log.error("Hostel release failed for institution {}", t.getCode(), e);
            }
        }
    }
}
