package tz.co.msingi.cims.ipt;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.ipt.IptDtos.AssessmentRequest;
import tz.co.msingi.cims.ipt.IptDtos.DecisionRequest;
import tz.co.msingi.cims.ipt.IptDtos.EligibleStudent;
import tz.co.msingi.cims.ipt.IptDtos.LogbookRequest;
import tz.co.msingi.cims.ipt.IptDtos.LogbookView;
import tz.co.msingi.cims.ipt.IptDtos.MyIpt;
import tz.co.msingi.cims.ipt.IptDtos.OrganisationRequest;
import tz.co.msingi.cims.ipt.IptDtos.OrganisationView;
import tz.co.msingi.cims.ipt.IptDtos.PeriodRequest;
import tz.co.msingi.cims.ipt.IptDtos.PeriodView;
import tz.co.msingi.cims.ipt.IptDtos.PlacementRequest;
import tz.co.msingi.cims.ipt.IptDtos.PlacementSummary;
import tz.co.msingi.cims.ipt.IptDtos.PlacementUpdate;
import tz.co.msingi.cims.ipt.IptDtos.PlacementView;
import tz.co.msingi.cims.ipt.IptDtos.ReviewRequest;
import tz.co.msingi.cims.ipt.IptDtos.StaffOption;
import tz.co.msingi.cims.ipt.IptDtos.StudentRequest;
import tz.co.msingi.cims.ipt.IptDtos.TransferResult;
import tz.co.msingi.cims.ipt.IptDtos.VisitRequest;

import java.util.List;
import java.util.Map;

/** Industrial practical training (FR-IPT-01..03, BP-13). */
@Tag(name = "Industrial practical training")
@RestController
@RequiredArgsConstructor
public class IptController {

    /** Any staff member may be an institution supervisor; the service checks the placement. */
    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR',"
            + "'HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR','LECTURER','ASSESSOR','EXAMINATIONS_OFFICER','BURSAR','ACCOUNTANT','HOSTEL_WARDEN',"
            + "'IPT_COORDINATOR','CLEARANCE_OFFICER','AUDITOR')";
    private static final String VIEWERS = "hasAnyRole('INSTITUTION_ADMIN','IPT_COORDINATOR','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT',"
            + "'EXAMINATIONS_OFFICER','MANAGEMENT_VIEWER')";
    private static final String COORDINATORS = "hasAnyRole('INSTITUTION_ADMIN','IPT_COORDINATOR')";

    private final IptService ipt;

    @GetMapping("/api/ipt/organisations")
    @PreAuthorize(VIEWERS)
    public List<OrganisationView> organisations() {
        return ipt.organisations();
    }

    @PostMapping("/api/ipt/organisations")
    @PreAuthorize(COORDINATORS)
    @ResponseStatus(HttpStatus.CREATED)
    public OrganisationView createOrganisation(@Valid @RequestBody OrganisationRequest req) {
        return ipt.saveOrganisation(null, req);
    }

    @PutMapping("/api/ipt/organisations/{id}")
    @PreAuthorize(COORDINATORS)
    public OrganisationView updateOrganisation(@PathVariable Long id, @Valid @RequestBody OrganisationRequest req) {
        return ipt.saveOrganisation(id, req);
    }

    @GetMapping("/api/ipt/periods")
    @PreAuthorize(VIEWERS)
    public List<PeriodView> periods() {
        return ipt.periods();
    }

    @PostMapping("/api/ipt/periods")
    @PreAuthorize(COORDINATORS)
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView createPeriod(@Valid @RequestBody PeriodRequest req) {
        return ipt.savePeriod(null, req);
    }

    @PutMapping("/api/ipt/periods/{id}")
    @PreAuthorize(COORDINATORS)
    public PeriodView updatePeriod(@PathVariable Long id, @Valid @RequestBody PeriodRequest req) {
        return ipt.savePeriod(id, req);
    }

    @GetMapping("/api/ipt/periods/{id}/students")
    @PreAuthorize(VIEWERS)
    public List<EligibleStudent> eligible(@PathVariable Long id) {
        return ipt.eligible(id);
    }

    @GetMapping("/api/ipt/periods/{id}/placements")
    @PreAuthorize(VIEWERS)
    public List<PlacementSummary> placements(@PathVariable Long id) {
        return ipt.placements(id);
    }

    @PostMapping("/api/ipt/periods/{id}/transfer")
    @PreAuthorize(COORDINATORS)
    public TransferResult transfer(@PathVariable Long id) {
        return ipt.transfer(id);
    }

    @GetMapping("/api/ipt/staff")
    @PreAuthorize(COORDINATORS)
    public List<StaffOption> staff() {
        return ipt.staffOptions();
    }

    @GetMapping("/api/ipt/supervised")
    @PreAuthorize(STAFF)
    public List<PlacementSummary> supervised() {
        return ipt.supervised();
    }

    @PostMapping("/api/ipt/placements")
    @PreAuthorize(COORDINATORS)
    public PlacementView assign(@Valid @RequestBody PlacementRequest req) {
        return ipt.assign(req);
    }

    @GetMapping("/api/ipt/placements/{id}")
    @PreAuthorize(STAFF)
    public PlacementView placement(@PathVariable Long id) {
        return ipt.get(id);
    }

    @PutMapping("/api/ipt/placements/{id}")
    @PreAuthorize(COORDINATORS)
    public PlacementView update(@PathVariable Long id, @Valid @RequestBody PlacementUpdate req) {
        return ipt.update(id, req);
    }

    @PostMapping("/api/ipt/placements/{id}/decision")
    @PreAuthorize(COORDINATORS)
    public PlacementView decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return ipt.decide(id, req);
    }

    @PostMapping("/api/ipt/placements/{id}/cancel")
    @PreAuthorize(COORDINATORS)
    public PlacementView cancel(@PathVariable Long id, @RequestBody Map<String, String> req) {
        String reason = req.getOrDefault("reason", "");
        if (reason.isBlank()) throw new tz.co.msingi.cims.common.exception.BusinessRuleException("REASON_REQUIRED", "Give the reason");
        return ipt.cancel(id, reason);
    }

    @GetMapping(value = "/api/ipt/placements/{id}/letter", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> letter(@PathVariable Long id) {
        return pdf(ipt.letter(id));
    }

    @PostMapping("/api/ipt/placements/{id}/visits")
    @PreAuthorize(STAFF)
    public PlacementView visit(@PathVariable Long id, @Valid @RequestBody VisitRequest req) {
        return ipt.visit(id, req);
    }

    @PutMapping("/api/ipt/placements/{id}/assessments")
    @PreAuthorize(STAFF)
    public PlacementView assess(@PathVariable Long id, @Valid @RequestBody AssessmentRequest req) {
        return ipt.assess(id, req);
    }

    @PostMapping("/api/ipt/logbook/{id}/review")
    @PreAuthorize(STAFF)
    public LogbookView review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return ipt.review(id, req);
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/ipt")
    @PreAuthorize("hasRole('STUDENT')")
    public MyIpt mine() {
        return ipt.mine();
    }

    @PostMapping("/api/student/ipt/placements")
    @PreAuthorize("hasRole('STUDENT')")
    public MyIpt request(@Valid @RequestBody StudentRequest req) {
        return ipt.request(req);
    }

    @PutMapping("/api/student/ipt/logbook")
    @PreAuthorize("hasRole('STUDENT')")
    public PlacementView logbook(@Valid @RequestBody LogbookRequest req) {
        return ipt.writeLogbook(req);
    }

    @GetMapping(value = "/api/student/ipt/letter", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<byte[]> myLetter() {
        return pdf(ipt.myLetter());
    }

    private static ResponseEntity<byte[]> pdf(PlacementLetter.Download d) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF).body(d.content());
    }
}
