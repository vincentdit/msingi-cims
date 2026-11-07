package tz.co.msingi.cims.console.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.jobs.JobLock;

import java.time.Duration;

/** Every minute: asks Msingi's aggregator about prompts still waiting to pay Msingi invoices, and closes those nobody answered. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MsingiPhonePaymentJob {

    private final JobLock jobLock;
    private final MsingiPhonePaymentService payments;

    @Scheduled(fixedDelayString = "${CIMS_COLLECTION_PHONE_SWEEP_MS:${cims.collection.phone-sweep-ms:60000}}", initialDelay = 75000)
    public void run() {
        jobLock.runOnce("msingi-phone-payments", Duration.ofMinutes(5), Duration.ofSeconds(30), this::runNow);
    }

    public void runNow() {
        try {
            payments.sweep();
        } catch (RuntimeException e) {
            log.error("Msingi phone payment sweep failed", e);
        }
    }
}
