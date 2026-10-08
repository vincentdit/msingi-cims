package tz.co.msingi.cims.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.accounts.ReceiptService;
import tz.co.msingi.cims.finance.accounts.SimulatedPaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

/** The signed-in applicant's (and new student's) fees: statement and control numbers. */
@Tag(name = "Applicant self-service")
@RestController
@RequestMapping("/api/applicant")
@PreAuthorize("hasRole('APPLICANT')")
@RequiredArgsConstructor
public class ApplicantAccountController {

    private final AccountService accounts;
    private final SimulatedPaymentService simulator;
    private final ReceiptService receipts;

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
    public Statement simulatePayment(@PathVariable Long id, @RequestBody(required = false) SimulatedPayment req) {
        return simulator.payMine(id, req == null ? null : req.amount());
    }

    @Operation(summary = "Receipt for one of my payments (PDF)")
    @GetMapping(value = "/receipt.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receipt(@org.springframework.web.bind.annotation.RequestParam("no") String receiptNo) {
        return ReceiptService.asDownload(receiptNo, receipts.mine(receiptNo));
    }
}
