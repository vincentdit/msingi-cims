package tz.co.msingi.cims.console.billing;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.console.billing.BillingModels.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Msingi billing in the platform console: contracts, invoices, payments, statements and Msingi's invoice details. */
@Tag(name = "Msingi billing (platform)")
@RestController
@RequestMapping("/api/platform/billing")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformBillingController {

    private final MsingiBillingService service;
    private final MsingiBillingJob job;
    private final MsingiCollectionService collection;
    private final MsingiStatementService statements;
    private final MsingiServicesService services;

    public record Prepare(Long tenantId, String kind, LocalDate from, LocalDate to) {
    }

    public record Reason(String reason) {
    }

    public record End(LocalDate endDate) {
    }

    @GetMapping("/overview")
    public Overview overview(@RequestParam(required = false) Integer year) {
        return service.overview(year == null ? MsingiBillingService.today().getYear() : year);
    }

    @GetMapping("/settings")
    public Settings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    public Settings saveSettings(@RequestBody Settings s) {
        return service.saveSettings(s);
    }

    @PostMapping(value = "/statements", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public MsingiStatementService.ImportView importStatement(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                                                             @RequestParam String provider) throws java.io.IOException {
        return statements.importStatement(file.getOriginalFilename(), file.getBytes(), provider);
    }

    @GetMapping("/statements")
    public List<MsingiStatementService.ImportView> statements() {
        return statements.recent();
    }

    @GetMapping("/services")
    public List<MsingiServicesService.ServiceView> services() {
        return services.all();
    }

    @PostMapping("/services")
    public MsingiServicesService.ServiceView createService(@jakarta.validation.Valid @RequestBody MsingiServicesService.ServiceInput in) {
        return services.save(null, in);
    }

    @PutMapping("/services/{id}")
    public MsingiServicesService.ServiceView updateService(@PathVariable Long id, @jakarta.validation.Valid @RequestBody MsingiServicesService.ServiceInput in) {
        return services.save(id, in);
    }

    @GetMapping("/service-orders")
    public List<MsingiServicesService.OrderView> serviceOrders(@RequestParam(defaultValue = "all") String status) {
        return services.orders(status);
    }

    @PostMapping("/service-orders/{id}/delivered")
    public MsingiServicesService.OrderView delivered(@PathVariable Long id, @RequestBody(required = false) MsingiServicesService.Delivered d) {
        return services.markDelivered(id, d);
    }

    @GetMapping("/pay-methods")
    public Map<String, String> payMethods() {
        return service.payMethods();
    }

    @PutMapping("/pay-methods")
    public Map<String, String> savePayMethods(@RequestBody Map<String, String> in) {
        return service.savePayMethods(in);
    }

    @GetMapping("/sms-pricing")
    public MsingiBillingService.SmsPricing smsPricing() {
        return service.smsPricing();
    }

    @PutMapping("/sms-pricing")
    public MsingiBillingService.SmsPricing saveSmsPricing(@RequestBody MsingiBillingService.SmsPricing p) {
        return service.saveSmsPricing(p);
    }

    public record ContractSmsPrice(java.math.BigDecimal pricePerSms) {
    }

    @GetMapping("/contracts/{tenantId}/sms-price")
    public ContractSmsPrice contractSmsPrice(@PathVariable Long tenantId) {
        return new ContractSmsPrice(service.contractSmsPrice(tenantId));
    }

    @PutMapping("/contracts/{tenantId}/sms-price")
    public ContractSmsPrice setContractSmsPrice(@PathVariable Long tenantId, @RequestBody ContractSmsPrice p) {
        service.setContractSmsPrice(tenantId, p.pricePerSms());
        return new ContractSmsPrice(service.contractSmsPrice(tenantId));
    }

    @GetMapping("/collection")
    public MsingiCollectionService.View collection() {
        return collection.view();
    }

    @PutMapping("/collection")
    public MsingiCollectionService.View saveCollection(@jakarta.validation.Valid @RequestBody MsingiCollectionService.SaveRequest req) {
        return collection.save(req);
    }

    @PostMapping("/collection/test")
    public MsingiCollectionService.View testCollection() {
        return collection.test();
    }

    @PostMapping("/collection/notify-address")
    public MsingiCollectionService.View newNotifyAddress() {
        return collection.newNotifyAddress();
    }

    @GetMapping("/settings/logo")
    public ResponseEntity<byte[]> logo() {
        MsingiBillingService.Logo l = service.logo();
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(l.contentType()))
                .header("Cache-Control", "no-cache").body(l.bytes());
    }

    @PutMapping(value = "/settings/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Settings uploadLogo(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return service.saveLogo(file.getBytes());
    }

    @DeleteMapping("/settings/logo")
    public Settings removeLogo() {
        return service.saveLogo(null);
    }

    @GetMapping("/contracts/{tenantId}")
    public List<Contract> contracts(@PathVariable Long tenantId) {
        return service.contracts(tenantId);
    }

    @PutMapping("/contracts/{tenantId}")
    public Contract saveContract(@PathVariable Long tenantId, @RequestBody ContractInput in) {
        return service.saveContract(tenantId, in);
    }

    @PostMapping("/contracts/{tenantId}/end")
    public Contract endContract(@PathVariable Long tenantId, @RequestBody End e) {
        return service.endContract(tenantId, e.endDate());
    }

    @GetMapping("/usage/{tenantId}")
    public Usage usage(@PathVariable Long tenantId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.usage(tenantId, from, to);
    }

    @GetMapping("/invoices")
    public MsingiBillingService.InvoicePage invoices(@RequestParam(defaultValue = "all") String status, @RequestParam(required = false) Long tenantId,
                                                     @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "25") int size) {
        return service.invoices(status, tenantId, q, page, size);
    }

    @PostMapping("/invoices")
    public InvoiceView prepare(@RequestBody Prepare p) {
        return service.prepare(p.tenantId(), p.kind(), p.from(), p.to());
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView invoice(@PathVariable Long id) {
        return service.invoiceView(id);
    }

    @PutMapping("/invoices/{id}")
    public InvoiceView update(@PathVariable Long id, @RequestBody InvoiceInput in) {
        return service.updateDraft(id, in);
    }

    @PostMapping("/invoices/{id}/issue")
    public InvoiceView issue(@PathVariable Long id) {
        return service.issue(id);
    }

    @PostMapping("/invoices/{id}/cancel")
    public InvoiceView cancel(@PathVariable Long id, @RequestBody Reason r) {
        return service.cancel(id, r.reason());
    }

    @PostMapping("/invoices/{id}/remind")
    public InvoiceView remind(@PathVariable Long id) {
        return service.remind(id);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        return service.invoicePdf(id, true).download();
    }

    @GetMapping("/payments")
    public List<PaymentRow> payments(@RequestParam(required = false) Long tenantId, @RequestParam(required = false) String q) {
        return service.payments(tenantId, q);
    }

    @PostMapping("/payments")
    public PaymentRow record(@RequestBody PaymentInput in) {
        return service.record(in);
    }

    @PostMapping("/payments/{id}/reverse")
    public PaymentRow reverse(@PathVariable Long id, @RequestBody Reason r) {
        return service.reverse(id, r.reason());
    }

    @GetMapping("/payments/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        return service.receiptPdf(id, true).download();
    }

    @GetMapping("/statements/{tenantId}")
    public Statement statement(@PathVariable Long tenantId) {
        return service.statement(tenantId);
    }

    @GetMapping("/statements/{tenantId}/pdf")
    public ResponseEntity<byte[]> statementPdf(@PathVariable Long tenantId) {
        return service.statementPdf(tenantId).download();
    }

    /** Runs the billing job now (prepares what is due, sends reminders). */
    @PostMapping("/run")
    public Map<String, Integer> run() {
        return job.runNow();
    }
}
