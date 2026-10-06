package tz.co.msingi.cims.admission.letter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.msingi.cims.admission.offer.OfferAcceptedEvent;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/**
 * Issues the admission letter once an acceptance has been committed. If the window's joining
 * instructions are not set yet, the acceptance still stands and the office issues the letters later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LetterOnAcceptance {

    private final AdmissionLetterService letters;
    private final TenantRepository tenants;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccepted(OfferAcceptedEvent event) {
        var tenant = tenants.findById(event.tenantId()).orElseThrow();
        try {
            TenantContext.callAs(new TenantContext.TenantInfo(tenant.getId(), tenant.getCode()), () -> letters.issueInNewTransaction(event.applicationId()));
        } catch (BusinessRuleException e) {
            log.info("Admission letter for application {} not issued yet: {}", event.applicationId(), e.getMessage());
        }
    }
}
