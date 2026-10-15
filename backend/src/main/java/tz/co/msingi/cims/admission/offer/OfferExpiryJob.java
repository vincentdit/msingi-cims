package tz.co.msingi.cims.admission.offer;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Closes expired admission offers in every active institution (each in its own tenant context). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfferExpiryJob {

    private final JobLock jobLock;

    private final TenantRepository tenants;
    private final OfferService offers;

    @Scheduled(cron = "${cims.offers.expiry-cron:0 5 * * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("offer-expiry", Duration.ofMinutes(50), Duration.ofMinutes(2), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(new TenantContext.TenantInfo(t.getId(), t.getCode()), offers::lapseExpired);
            } catch (RuntimeException e) {
                log.error("Offer expiry failed for institution {}", t.getCode(), e); // one institution must not block the others
            }
        }
    }
}
