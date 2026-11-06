package tz.co.msingi.cims.finance.aggregator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Mobile money through the institution's aggregator: its settings (Integrations › Mobile money), payers' phone prompts, and the bursary's list. */
@Tag(name = "Mobile money")
@RestController
@RequiredArgsConstructor
public class MobileMoneyController {

    private static final String SETTINGS_READ = "hasAnyRole('INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT')";
    private static final String SETTINGS_WRITE = "hasAnyRole('INSTITUTION_ADMIN', 'BURSAR')";

    private final CollectionAccountService settings;
    private final PhonePaymentService payments;

    @GetMapping("/api/collection-account")
    @PreAuthorize(SETTINGS_READ)
    public CollectionAccountService.View view() {
        return settings.view();
    }

    @PutMapping("/api/collection-account")
    @PreAuthorize(SETTINGS_WRITE)
    public CollectionAccountService.View save(@Valid @RequestBody CollectionAccountService.SaveRequest req) {
        return settings.save(req);
    }

    @Operation(summary = "Prove the aggregator credentials (a token or a harmless query)")
    @PostMapping("/api/collection-account/test")
    @PreAuthorize(SETTINGS_WRITE)
    public CollectionAccountService.View test() {
        return settings.test();
    }

    @Operation(summary = "A new notification address for the aggregator (the old one stops working)")
    @PostMapping("/api/collection-account/notify-address")
    @PreAuthorize(SETTINGS_WRITE)
    public CollectionAccountService.View newNotifyAddress() {
        return settings.newNotifyAddress();
    }

    @GetMapping("/api/phone-payments")
    @PreAuthorize(SETTINGS_READ)
    public List<PhonePaymentService.View> list(@RequestParam(required = false) String status) {
        return payments.list(status);
    }

    @PostMapping("/api/phone-payments/{id}/check")
    @PreAuthorize(SETTINGS_READ)
    public PhonePaymentService.View checkNow(@PathVariable Long id) {
        return payments.checkNow(id);
    }

    // ------------------------------------------------------------------ payers

    @Operation(summary = "Prompt my phone to pay one of my invoices (applicant)")
    @PostMapping("/api/applicant/invoices/{id}/phone-payment")
    @PreAuthorize("hasRole('APPLICANT')")
    public PhonePaymentService.View applicantPay(@PathVariable Long id, @RequestBody(required = false) PhonePaymentService.PhoneRequest req) {
        return payments.requestMine(id, req, "APPLICANT");
    }

    @GetMapping("/api/applicant/phone-payments/{id}")
    @PreAuthorize("hasRole('APPLICANT')")
    public PhonePaymentService.View applicantStatus(@PathVariable Long id) {
        return payments.mine(id);
    }

    @Operation(summary = "Prompt my phone to pay one of my invoices (student)")
    @PostMapping("/api/student/invoices/{id}/phone-payment")
    @PreAuthorize("hasRole('STUDENT')")
    public PhonePaymentService.View studentPay(@PathVariable Long id, @RequestBody(required = false) PhonePaymentService.PhoneRequest req) {
        return payments.requestMine(id, req, "STUDENT");
    }

    @GetMapping("/api/student/phone-payments/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    public PhonePaymentService.View studentStatus(@PathVariable Long id) {
        return payments.mine(id);
    }
}
