package tz.co.msingi.cims.integration.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.tamisemi.TamisemiService;
import tz.co.msingi.cims.finance.heslb.HeslbService;
import tz.co.msingi.cims.finance.muse.MuseService;
import tz.co.msingi.cims.registry.nactvet.NactvetRegistrationService;

import java.time.LocalDate;
import java.util.List;

/** The exchanges with HESLB, MUSE, TAMISEMI and NACTVET (student registration) started by staff. */
@Tag(name = "Integrations — exchanges")
@RestController
@RequiredArgsConstructor
public class ExchangeController {

    private static final String BURSARY = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private static final String HESLB_READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','REGISTRAR')";
    private static final String ADMISSIONS = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";

    private final HeslbService heslb;
    private final MuseService muse;
    private final TamisemiService tamisemi;
    private final NactvetRegistrationService nactvet;

    // ------------------------------------------------------------------ HESLB (FR-INT-05)

    @Operation(summary = "HESLB: exchanges, sponsored students and disbursements for a year")
    @GetMapping("/api/heslb")
    @PreAuthorize(HESLB_READ)
    public HeslbService.Overview heslb(@RequestParam(required = false) Long academicYearId) {
        return heslb.overview(academicYearId);
    }

    @Operation(summary = "HESLB: send registration (first year) or continuation (later years) data")
    @PostMapping("/api/heslb/registrations")
    @PreAuthorize(REGISTRY)
    public HeslbService.Exchange sendRegistrations(@RequestParam(required = false) Long academicYearId, @RequestParam String kind) {
        return heslb.sendRegistrations(academicYearId, kind);
    }

    @Operation(summary = "HESLB: fetch beneficiaries and create or update HESLB sponsorships")
    @PostMapping("/api/heslb/beneficiaries")
    @PreAuthorize(BURSARY)
    public HeslbService.Exchange beneficiaries(@RequestParam(required = false) Long academicYearId) {
        return heslb.syncBeneficiaries(academicYearId);
    }

    @Operation(summary = "HESLB: fetch disbursements paid to the institution")
    @PostMapping("/api/heslb/disbursements")
    @PreAuthorize(BURSARY)
    public HeslbService.Exchange disbursements(@RequestParam(required = false) Long academicYearId) {
        return heslb.fetchDisbursements(academicYearId);
    }

    @Operation(summary = "HESLB: record a disbursement as payment of a HESLB sponsor invoice")
    @PostMapping("/api/heslb/disbursements/{id}/record")
    @PreAuthorize(BURSARY)
    public HeslbService.DisbursementRow record(@PathVariable Long id, @RequestParam Long invoiceId) {
        return heslb.record(id, invoiceId);
    }

    // ------------------------------------------------------------------ MUSE (FR-INT-07)

    @Operation(summary = "MUSE: the journal a period would post")
    @GetMapping("/api/muse/preview")
    @PreAuthorize(BURSARY)
    public MuseService.Preview musePreview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return muse.preview(from, to);
    }

    @PostMapping("/api/muse/postings")
    @PreAuthorize(BURSARY)
    public MuseService.Posting musePost(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return muse.post(from, to);
    }

    @GetMapping("/api/muse/postings")
    @PreAuthorize(BURSARY + " or hasRole('AUDITOR')")
    public List<MuseService.Posting> musePostings() {
        return muse.list();
    }

    @GetMapping("/api/muse/postings/{id}/lines")
    @PreAuthorize(BURSARY + " or hasRole('AUDITOR')")
    public List<MuseService.Line> museLines(@PathVariable Long id) {
        return muse.lines(id);
    }

    // ------------------------------------------------------------------ TAMISEMI (FR-INT-08)

    @Operation(summary = "TAMISEMI: fetch the year's selections into a selections import awaiting sign-off")
    @PostMapping("/api/tamisemi/fetch")
    @PreAuthorize(ADMISSIONS)
    public TamisemiService.Fetch tamisemiFetch(@RequestParam Long windowId, @RequestParam(required = false) Integer year) {
        return tamisemi.fetch(windowId, year);
    }

    @GetMapping("/api/tamisemi/fetches")
    @PreAuthorize(ADMISSIONS)
    public List<TamisemiService.Fetch> tamisemiHistory() {
        return tamisemi.history();
    }

    // ------------------------------------------------------------------ NACTVET student registration (FR-REG-07)

    @Operation(summary = "NACTVET registration: students waiting and batches sent")
    @GetMapping("/api/nactvet-registration")
    @PreAuthorize(REGISTRY + " or hasRole('MANAGEMENT_VIEWER')")
    public NactvetRegistrationService.Overview nactvetOverview(@RequestParam(required = false) Long academicYearId) {
        return nactvet.overview(academicYearId);
    }

    @PostMapping("/api/nactvet-registration")
    @PreAuthorize(REGISTRY)
    public NactvetRegistrationService.Batch nactvetSubmit(@RequestParam(required = false) Long academicYearId) {
        return nactvet.submit(academicYearId);
    }

    @PostMapping("/api/nactvet-registration/{id}/refresh")
    @PreAuthorize(REGISTRY)
    public NactvetRegistrationService.Batch nactvetRefresh(@PathVariable Long id) {
        return nactvet.refresh(id);
    }

    @GetMapping("/api/nactvet-registration/{id}/items")
    @PreAuthorize(REGISTRY + " or hasRole('MANAGEMENT_VIEWER')")
    public List<NactvetRegistrationService.Item> nactvetItems(@PathVariable Long id) {
        return nactvet.items(id);
    }
}
