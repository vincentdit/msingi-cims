package tz.co.msingi.cims.finance.aggregator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;

/** Every minute: asks the aggregators about phone prompts still waiting, and closes those nobody answered within half an hour. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhonePaymentJob {

    private final JobLock jobLock;
    private final TenantRepository tenants;
    private final PhonePaymentService payments;

    @Scheduled(fixedDelayString = "${CIMS_COLLECTION_PHONE_SWEEP_MS:${cims.collection.phone-sweep-ms:60000}}", initialDelay = 60000)
    public void run() {
        jobLock.runOnce("phone-payments", Duration.ofMinutes(5), Duration.ofSeconds(30), this::runNow);
    }

    public void runNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus()) || t.usesGepg()) continue;
            try {
                TenantContext.runAs(t.toInfo(), payments::sweep);
            } catch (RuntimeException e) {
                log.error("Phone payment sweep failed for institution {}", t.getCode(), e);
            }
        }
    }
}
