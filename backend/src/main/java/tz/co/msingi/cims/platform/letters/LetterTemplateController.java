package tz.co.msingi.cims.platform.letters;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.admission.letter.AdmissionLetterRenderer;
import tz.co.msingi.cims.admission.letter.JoiningInstructions;
import tz.co.msingi.cims.admission.letter.LetterContent;
import tz.co.msingi.cims.ipt.PlacementLetter;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Letter templates")
@RestController
@RequestMapping("/api/letter-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','IPT_COORDINATOR','REGISTRAR')")
public class LetterTemplateController {

    private final LetterTemplateService service;
    private final TenantRepository tenants;

    @GetMapping
    public List<LetterTemplateService.TemplateView> list() {
        return service.list();
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','IPT_COORDINATOR')")
    public LetterTemplateService.TemplateView save(@PathVariable String code, @Valid @RequestBody LetterTemplateService.Save s) {
        return service.save(code, s);
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','IPT_COORDINATOR')")
    public LetterTemplateService.TemplateView reset(@PathVariable String code) {
        return service.reset(code);
    }

    @Operation(summary = "A sample letter (PDF) with this wording, before saving it")
    @PostMapping(value = "/{code}/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> preview(@PathVariable String code, @Valid @RequestBody LetterTemplateService.Save s) {
        service.view(code);
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        byte[] pdf;
        if (code.startsWith("IPT_")) {
            Map<String, String> v = new HashMap<>();
            v.put("primaryColor", "#1F4E8C");
            v.put("shortName", t.getShortName() == null ? "CIMS" : t.getShortName());
            v.put("institutionName", t.getName());
            v.put("nactvetNo", t.getNactvetRegNo() == null ? "" : t.getNactvetRegNo());
            v.put("contactLine", (t.getContactPhone() == null ? "" : t.getContactPhone()) + (t.getContactEmail() == null ? "" : " / " + t.getContactEmail()));
            v.put("contactName", "The Human Resources Manager");
            v.put("contactTitle", "");
            v.put("organisation", "Tanzania Ports Authority");
            v.put("address", "P.O. Box 9184, Dar es Salaam");
            v.put("salutation", "Sir/Madam");
            v.put("letterNo", "SAMPLE/IPT/2028/0001");
            v.put("issuedOn", "1 June 2028");
            v.put("studentName", "Asha Juma Mwinyi");
            v.put("registrationNo", "SAMPLE/NTA6/27/0001");
            v.put("programme", "Ordinary Diploma in Information Technology");
            v.put("startsOn", "6 July 2028");
            v.put("endsOn", "28 August 2028");
            v.put("unit", "ICT Section");
            v.put("workplaceSupervisor", "Eng. Baraka Said, ICT Manager");
            v.put("institutionSupervisor", "Mr. Daudi Lema");
            String intro = code.equals(LetterTemplateService.IPT_INTRO) ? s.body() : service.body(LetterTemplateService.IPT_INTRO);
            String req = code.equals(LetterTemplateService.IPT_REQUESTS) ? s.body() : service.body(LetterTemplateService.IPT_REQUESTS);
            pdf = PlacementLetter.render(v, intro, req, "sample placement letter");
        } else {
            Map<String, String> wording = new HashMap<>();
            for (String c : List.of(LetterTemplateService.ADMISSION_INTRO, LetterTemplateService.ADMISSION_TERMS, LetterTemplateService.ADMISSION_PAYMENT)) {
                wording.put(c, c.equals(code) ? s.body() : service.body(c));
            }
            Object colour = t.getBranding() == null ? null : t.getBranding().get("primaryColor");
            LetterContent sample = new LetterContent(t.getName(), t.getShortName(), t.getNactvetRegNo(), t.getContactPhone(), t.getContactEmail(),
                    colour == null ? "" : colour.toString(), "SAMPLE/ADM/2027/000001", 1, LocalDate.now(), "Asha Juma Mwinyi",
                    "P.O. Box 123\nMoshi, Kilimanjaro", "SAMPLE/APP/2027/000123", "NV2027-000123", "Ordinary Diploma in Information Technology",
                    "Ordinary Diploma (NTA Level 6)", "NTA6-ICT-01", 6, "2027/2028", "September Intake", OffsetDateTime.now(),
                    new JoiningInstructions(LocalDate.now().plusMonths(1), "08:00", "Main Hall", null, List.of("Original CSEE certificate"),
                            List.of(), null, List.of(), "Principal's name", "Principal"),
                    "SAMP-LE00-0000-0000", "https://example.ac.tz/verify/SAMP-LE00-0000-0000").withWording(wording);
            pdf = AdmissionLetterRenderer.pdf(sample);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition", "inline; filename=\"sample-letter.pdf\"").body(pdf);
    }
}
