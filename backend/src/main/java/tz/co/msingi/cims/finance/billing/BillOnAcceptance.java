package tz.co.msingi.cims.finance.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.msingi.cims.admission.offer.OfferAcceptedEvent;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/** Raises the first-year invoice once an acceptance has committed (BP-04 step 5, BP-06 trigger). */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillOnAcceptance {

    private final AnnualBillingService billing;
    private final TenantRepository tenants;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccepted(OfferAcceptedEvent event) {
        var tenant = tenants.findById(event.tenantId()).orElseThrow();
        try {
            TenantContext.callAs(new TenantContext.TenantInfo(tenant.getId(), tenant.getCode()),
                    () -> billing.billAcceptedApplicationInNewTransaction(event.applicationId()))
                    .ifPresentOrElse(inv -> log.info("Annual invoice {} raised for application {}", inv.getInvoiceNo(), event.applicationId()),
                            () -> log.info("No approved fee structure yet for application {}; it will be billed in a billing run", event.applicationId()));
        } catch (RuntimeException e) {
            // the acceptance stands; the Bursar's billing run picks the applicant up
            log.warn("Could not bill application {} on acceptance: {}", event.applicationId(), e.getMessage());
        }
    }
}
