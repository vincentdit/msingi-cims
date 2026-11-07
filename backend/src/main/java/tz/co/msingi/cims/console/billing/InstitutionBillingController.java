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
    private final MsingiCollectionService collection;
    private final MsingiPhonePaymentService phone;

    public record BuySms(int units) {
    }

    @GetMapping
    public Account account() {
        return service.account();
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public InvoiceView invoice(@PathVariable Long id) {
        return service.ownInvoice(id);
    }

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
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

    /** What an SMS bundle costs, how to pay Msingi, and the SMS invoices still waiting for payment (administrators and bursars). */
    @GetMapping("/sms-offer")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public MsingiBillingService.SmsOffer smsOffer() {
        MsingiCollectionService.Prompt p = collection.prompt();
        return service.smsOffer(p.enabled(), p.needsNetwork(), p.provider());
    }

    public record PayOptions(boolean phonePrompt, boolean phoneNetwork, String phoneProvider, boolean canSimulate) {
    }

    /** Whether Msingi's invoices can be paid by a prompt on a phone now (and through whom), for the Pay window. */
    @GetMapping("/pay-options")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public PayOptions payOptions() {
        MsingiCollectionService.Prompt p = collection.prompt();
        return new PayOptions(p.enabled(), p.needsNetwork(), p.provider(), service.simulationAllowed());
    }

    /** Buys an SMS bundle: the invoice is issued at once with its payment reference; the SMS are added as soon as it is paid. */
    @PostMapping("/sms-bundles")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public InvoiceView buySms(@RequestBody BuySms req) {
        return service.buySms(req.units(), tz.co.msingi.cims.platform.security.CurrentUser.name());
    }

    /** Sends a mobile money prompt to a phone for what is left to pay on one of the institution's Msingi invoices. */
    @PostMapping("/invoices/{id}/phone-payment")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public MsingiPhonePaymentService.View payByPhone(@PathVariable Long id, @RequestBody MsingiPhonePaymentService.PhoneRequest req) {
        return phone.request(id, req, tz.co.msingi.cims.platform.security.CurrentUser.name());
    }

    @GetMapping("/phone-payments/{id}")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public MsingiPhonePaymentService.View phonePayment(@PathVariable Long id) {
        return phone.mine(id);
    }

    /** Testing period only (cims.payments.allow-simulation): pays the invoice as if by mobile money. */
    @PostMapping("/invoices/{id}/simulate-payment")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR')")
    public PaymentRow simulate(@PathVariable Long id) {
        return service.simulateOwn(id);
    }
}
