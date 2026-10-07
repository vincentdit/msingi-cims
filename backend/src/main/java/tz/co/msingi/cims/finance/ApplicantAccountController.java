package tz.co.msingi.cims.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.finance.service.BillingService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The signed-in applicant's (and new student's) fees: statement and control numbers. */
@Tag(name = "Applicant self-service")
@RestController
@RequestMapping("/api/applicant")
@PreAuthorize("hasRole('APPLICANT')")
@RequiredArgsConstructor
public class ApplicantAccountController {

    private final AccountService accounts;
    private final BillingService billing;
    private final GepgProperties gepg;

    public record SimulatedPayment(@Positive BigDecimal amount) {
    }

    @GetMapping("/account")
    public Statement account() {
        return accounts.mine();
    }

    @PostMapping("/invoices/{id}/control-number")
    public Statement controlNumber(@PathVariable Long id) {
        return accounts.requestMyControlNumber(id);
    }

    @Operation(summary = "DEVELOPMENT ONLY — pay one of my invoices through the simulated GePG (whole balance, or the amount given)")
    @PostMapping("/invoices/{id}/simulate-payment")
    @Transactional
    public Statement simulatePayment(@PathVariable Long id, @RequestBody(required = false) SimulatedPayment req) {
        if (!gepg.isSimulated()) {
            throw new BusinessRuleException("NOT_AVAILABLE", "Payment simulation is only available with simulated GePG");
        }
        Invoice inv = accounts.requireMine(id);
        ControlNumber cn = billing.ensureControlNumber(inv.getId());
        BigDecimal amount = req == null || req.amount() == null ? inv.balance() : req.amount();
        billing.recordPayment(new BillingService.PaymentNotification(cn.getBillId(), cn.getControlNo(),
                "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "PSP-" + System.currentTimeMillis(), "Simulated Bank", "MOBILE", amount, inv.getPayerPhone(), OffsetDateTime.now()));
        return accounts.mine();
    }
}
