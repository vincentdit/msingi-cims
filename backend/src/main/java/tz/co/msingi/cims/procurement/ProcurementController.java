package tz.co.msingi.cims.procurement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Procurement (FR-PRC-01..05). */
@Tag(name = "Procurement")
@RestController
@RequiredArgsConstructor
public class ProcurementController {

    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','PROCUREMENT_OFFICER','BURSAR','ACCOUNTANT','STORES_OFFICER','MANAGEMENT_VIEWER','AUDITOR')";
    static final String PROCURE = "hasAnyRole('INSTITUTION_ADMIN','PROCUREMENT_OFFICER')";

    private final ProcurementService procurement;

    public record NoteRequest(String note) {
    }

    @GetMapping("/api/procurement/overview")
    @PreAuthorize(READ)
    public ProcurementService.Overview overview() {
        return procurement.overview();
    }

    @GetMapping("/api/procurement/suppliers")
    @PreAuthorize(READ)
    public List<ProcurementService.Supplier> suppliers(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean activeOnly) {
        return procurement.suppliers(q, activeOnly);
    }

    @PostMapping("/api/procurement/suppliers")
    @PreAuthorize(PROCURE)
    public ProcurementService.Supplier addSupplier(@RequestBody ProcurementService.SupplierRequest r) {
        return procurement.saveSupplier(null, r);
    }

    @PutMapping("/api/procurement/suppliers/{id}")
    @PreAuthorize(PROCURE)
    public ProcurementService.Supplier saveSupplier(@PathVariable Long id, @RequestBody ProcurementService.SupplierRequest r) {
        return procurement.saveSupplier(id, r);
    }

    @Operation(summary = "Ask to buy (any member of staff)")
    @PostMapping("/api/procurement/requisitions")
    @PreAuthorize(StaffAccess.ANY)
    public ProcurementService.RequisitionDetail requisition(@RequestBody ProcurementService.RequisitionRequest r) {
        return procurement.requisition(r);
    }

    @Operation(summary = "Requisitions you may see (mine=true: only your own)")
    @GetMapping("/api/procurement/requisitions")
    @PreAuthorize(StaffAccess.ANY)
    public List<ProcurementService.RequisitionView> requisitions(@RequestParam(required = false) String status, @RequestParam(defaultValue = "false") boolean mine) {
        return procurement.requisitions(status, mine);
    }

    @GetMapping("/api/procurement/requisitions/{id}")
    @PreAuthorize(StaffAccess.ANY)
    public ProcurementService.RequisitionDetail requisition(@PathVariable Long id) {
        return procurement.detail(id);
    }

    @Operation(summary = "The Head of Department endorses or turns down a requisition")
    @PostMapping("/api/procurement/requisitions/{id}/endorse")
    @PreAuthorize(StaffAccess.ANY)
    public ProcurementService.RequisitionDetail endorse(@PathVariable Long id, @RequestBody ProcurementService.DecisionRequest r) {
        return procurement.endorse(id, r);
    }

    @Operation(summary = "The Bursar or the Principal approves (funds available) or turns down a requisition")
    @PostMapping("/api/procurement/requisitions/{id}/approve")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','BURSAR','MANAGEMENT_VIEWER')")
    public ProcurementService.RequisitionDetail approve(@PathVariable Long id, @RequestBody ProcurementService.DecisionRequest r) {
        return procurement.approve(id, r);
    }

    @PostMapping("/api/procurement/requisitions/{id}/cancel")
    @PreAuthorize(StaffAccess.ANY)
    public ProcurementService.RequisitionDetail cancel(@PathVariable Long id, @RequestBody NoteRequest r) {
        return procurement.cancel(id, r.note());
    }

    @Operation(summary = "Record a supplier's quotation (multipart: supplierId, amount, deliveryDays, reference and an optional PDF or picture, up to 5 MB)")
    @PostMapping(value = "/api/procurement/requisitions/{id}/quotations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(PROCURE)
    public ProcurementService.RequisitionDetail quotation(@PathVariable Long id, @RequestParam Long supplierId, @RequestParam BigDecimal amount,
                                                          @RequestParam(required = false) Integer deliveryDays, @RequestParam(required = false) String reference,
                                                          @RequestPart(required = false) MultipartFile file) {
        return procurement.addQuotation(id, supplierId, amount, deliveryDays, reference, file);
    }

    @PostMapping("/api/procurement/quotations/{id}/choose")
    @PreAuthorize(PROCURE)
    public ProcurementService.RequisitionDetail choose(@PathVariable Long id, @RequestBody NoteRequest r) {
        return procurement.chooseQuotation(id, r.note());
    }

    @GetMapping("/api/procurement/quotations/{id}/file")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> quotationFile(@PathVariable Long id) {
        ProcurementService.StoredFile f = procurement.quotationFile(id);
        return UploadedFile.download(f.fileName(), f.contentType(), f.data());
    }

    @PostMapping("/api/procurement/orders")
    @PreAuthorize(PROCURE)
    public ProcurementService.OrderDetail issue(@RequestBody ProcurementService.OrderRequest r) {
        return procurement.issueOrder(r);
    }

    @GetMapping("/api/procurement/orders")
    @PreAuthorize(READ)
    public List<ProcurementService.OrderView> orders(@RequestParam(required = false) String status) {
        return procurement.orders(status);
    }

    @GetMapping("/api/procurement/orders/{id}")
    @PreAuthorize(READ)
    public ProcurementService.OrderDetail order(@PathVariable Long id) {
        return procurement.order(id);
    }

    @GetMapping("/api/procurement/orders/{id}/pdf")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> orderPdf(@PathVariable Long id) {
        return UploadedFile.download("lpo-" + id + ".pdf", "application/pdf", procurement.orderPdf(id));
    }

    @PostMapping("/api/procurement/orders/{id}/cancel")
    @PreAuthorize(PROCURE)
    public ProcurementService.OrderDetail cancelOrder(@PathVariable Long id, @RequestBody NoteRequest r) {
        return procurement.cancelOrder(id, r.note());
    }

    @Operation(summary = "Goods received note: items go into stores or the asset register (Stores, not whoever issued the order)")
    @PostMapping("/api/procurement/orders/{id}/receipts")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','STORES_OFFICER')")
    public ProcurementService.OrderDetail receive(@PathVariable Long id, @RequestBody ProcurementService.ReceiveRequest r) {
        return procurement.receive(id, r);
    }

    @GetMapping("/api/procurement/register")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> register(@RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        return UploadedFile.download("procurement-" + y + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", procurement.register(y));
    }
}
