package tz.co.msingi.cims.finance.reconciliation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.ImportRequest;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.MonthView;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.NoteRequest;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.PostRequest;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.ReconciliationView;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.SignoffRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

/** Daily GePG reconciliation and monthly sign-off (FR-FIN-05). */
@Tag(name = "Finance — GePG reconciliation")
@RestController
@RequestMapping("/api/finance/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','AUDITOR','MANAGEMENT_VIEWER')";
    private static final String WORK = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT')";
    private static final String BURSAR = "hasAnyRole('INSTITUTION_ADMIN','BURSAR')";

    private final ReconciliationService service;

    @Operation(summary = "Month view: collections per day, reconciliation status and sign-off")
    @GetMapping
    @PreAuthorize(READ)
    public MonthView month(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.month(month);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ReconciliationView get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Import GePG's reconciliation file for a date (CSV); replaces an earlier import with no resolved lines")
    @PostMapping
    @PreAuthorize(WORK)
    public ReconciliationView importFile(@Valid @RequestBody ImportRequest req) {
        return service.importFile(req.date(), req.fileName(), req.csv());
    }

    @Operation(summary = "Ask GePG for a day's transactions (live GePG); the answer arrives by callback and is matched like a file")
    @PostMapping("/request-gepg")
    @PreAuthorize(WORK)
    public java.util.Map<String, Object> requestFromGepg(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return service.requestFromGepg(date);
    }

    @Operation(summary = "Post a payment GePG reports but CIMS never received")
    @PostMapping("/items/{itemId}/post")
    @PreAuthorize(WORK)
    public ReconciliationView post(@PathVariable Long itemId, @Valid @RequestBody(required = false) PostRequest req) {
        return service.post(itemId, req == null ? null : req.note());
    }

    @Operation(summary = "Resolve an exception with an explanation")
    @PostMapping("/items/{itemId}/resolve")
    @PreAuthorize(WORK)
    public ReconciliationView resolve(@PathVariable Long itemId, @Valid @RequestBody NoteRequest req) {
        return service.note(itemId, req.note());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(WORK)
    public ReconciliationView close(@PathVariable Long id) {
        return service.close(id);
    }

    @Operation(summary = "Bursar's monthly sign-off")
    @PostMapping("/months/{month}/sign-off")
    @PreAuthorize(BURSAR)
    public MonthView signOff(@PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month, @Valid @RequestBody(required = false) SignoffRequest req) {
        return service.signOff(month, req == null ? null : req.note());
    }

    @Operation(summary = "CIMS payments of a date in GePG's file layout")
    @GetMapping(value = "/cims-payments.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> cimsPayments(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("cims-payments-" + date + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.cimsPaymentsFile(date).getBytes(StandardCharsets.UTF_8));
    }
}
