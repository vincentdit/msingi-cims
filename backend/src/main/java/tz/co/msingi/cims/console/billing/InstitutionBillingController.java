package tz.co.msingi.cims.console.billing;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.console.billing.BillingModels.*;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

/** The institution's account with Msingi: its contract, invoices, payments and statement, for its administrators. */
@Tag(name = "Msingi subscription (institution)")
@RestController
@RequestMapping("/api/msingi-billing")
@PreAuthorize("hasRole('INSTITUTION_ADMIN')")
@RequiredArgsConstructor
public class InstitutionBillingController {

    private final MsingiBillingService service;

    @GetMapping
    public Account account() {
        return service.account();
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView invoice(@PathVariable Long id) {
        return service.ownInvoice(id);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        return service.invoicePdf(id, false).download();
    }

    @GetMapping("/payments/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        return service.receiptPdf(id, false).download();
    }

    @GetMapping("/statement")
    public Statement statement() {
        return service.statement(TenantContext.requireTenantId());
    }

    @GetMapping("/statement/pdf")
    public ResponseEntity<byte[]> statementPdf() {
        return service.statementPdf(TenantContext.requireTenantId()).download();
    }

    /** Testing period only (cims.payments.allow-simulation): pays the invoice as if by mobile money. */
    @PostMapping("/invoices/{id}/simulate-payment")
    public PaymentRow simulate(@PathVariable Long id) {
        return service.simulateOwn(id);
    }
}
