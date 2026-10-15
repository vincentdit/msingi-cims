package tz.co.msingi.cims.finance.accounts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.finance.service.BillingService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** DEVELOPMENT ONLY: pays one of the signed-in person's invoices through the simulated GePG or simulated collector. */
@Service
@RequiredArgsConstructor
public class SimulatedPaymentService {

    private final AccountService accounts;
    private final BillingService billing;
    private final GepgProperties gepg;
    private final tz.co.msingi.cims.finance.collection.CollectionProperties collection;
    private final tz.co.msingi.cims.finance.collection.PaymentChannelService channels;

    @Transactional
    public Statement payMine(Long invoiceId, BigDecimal amount) {
        boolean viaGepg = channels.usesGepg();
        if (viaGepg ? !gepg.isSimulated() : !collection.isSimulated()) {
            throw new BusinessRuleException("NOT_AVAILABLE", "Payment simulation is only available in the test system");
        }
        Invoice inv = accounts.requireMine(invoiceId);
        ControlNumber cn = billing.ensureControlNumber(inv.getId());
        BigDecimal paid = amount == null ? inv.balance() : amount;
        String psp = viaGepg ? "Simulated Bank" : channels.forPayers().channels().stream().findFirst()
                .map(tz.co.msingi.cims.finance.collection.PaymentChannelService.ChannelView::label).orElse("M-Pesa");
        billing.recordPayment(new BillingService.PaymentNotification(cn.getBillId(), cn.getControlNo(),
                "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "PSP-" + System.currentTimeMillis(), psp, "MOBILE", paid, inv.getPayerPhone(), OffsetDateTime.now()));
        return accounts.mine();
    }
}
