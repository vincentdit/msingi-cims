package tz.co.msingi.cims.admission.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationDetailView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationSummaryView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.CloseRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.OpenRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ReopenRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowEventView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowProgrammeRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowProgrammeView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowStats;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowView;
import tz.co.msingi.cims.admission.service.AdmissionWindowService;
import tz.co.msingi.cims.admission.service.ApplicationService;
import tz.co.msingi.cims.common.web.PageResponse;

import java.util.List;

/** Admissions Office back-office endpoints. */
@Tag(name = "Admissions office")
@RestController
@RequestMapping("/api/admission")
@RequiredArgsConstructor
public class AdmissionAdminController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','REGISTRAR','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER')";
    private static final String OPEN_CLOSE = "hasAnyRole('INSTITUTION_ADMIN','ADMISSIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final AdmissionWindowService windows;
    private final tz.co.msingi.cims.admission.service.QualificationCheckService qualificationChecks;
    private final ApplicationService applications;

    @GetMapping("/windows")
    @PreAuthorize(READ)
    public List<WindowView> windows() {
        return windows.list();
    }

    @GetMapping("/windows/{id}")
    @PreAuthorize(READ)
    public WindowView window(@PathVariable Long id) {
        return windows.get(id);
    }

    @GetMapping("/windows/{id}/stats")
    @PreAuthorize(READ)
    public WindowStats stats(@PathVariable Long id) {
        return windows.stats(id);
    }

    @PostMapping("/windows")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public WindowView create(@Valid @RequestBody WindowRequest req) {
        return windows.save(null, req);
    }

    @PutMapping("/windows/{id}")
    @PreAuthorize(WRITE)
    public WindowView update(@PathVariable Long id, @Valid @RequestBody WindowRequest req) {
        return windows.save(id, req);
    }

    @PostMapping("/windows/{id}/programmes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public WindowProgrammeView addProgramme(@PathVariable Long id, @Valid @RequestBody WindowProgrammeRequest req) {
        return windows.addProgramme(id, req);
    }

    @PutMapping("/windows/{id}/programmes/{wpId}")
    @PreAuthorize(WRITE)
    public WindowProgrammeView updateProgramme(@PathVariable Long id, @PathVariable Long wpId, @Valid @RequestBody WindowProgrammeRequest req) {
        return windows.updateProgramme(id, wpId, req);
    }

    /** Publishing, opening, closing and reopening windows: the Admissions Office, the administrator or the Deputy Principal (Academic). */
    @PostMapping("/windows/{id}/publish")
    @PreAuthorize(OPEN_CLOSE)
    public WindowView publish(@PathVariable Long id) {
        return windows.publish(id);
    }

    @PostMapping("/windows/{id}/open")
    @PreAuthorize(OPEN_CLOSE)
    public WindowView open(@PathVariable Long id, @Valid @RequestBody(required = false) OpenRequest req) {
        return windows.open(id, req);
    }

    @PostMapping("/windows/{id}/close")
    @PreAuthorize(OPEN_CLOSE)
    public WindowView close(@PathVariable Long id, @Valid @RequestBody CloseRequest req) {
        return windows.close(id, req);
    }

    @PostMapping("/windows/{id}/reopen")
    @PreAuthorize(OPEN_CLOSE)
    public WindowView reopen(@PathVariable Long id, @Valid @RequestBody ReopenRequest req) {
        return windows.reopen(id, req);
    }

    @GetMapping("/windows/{id}/events")
    @PreAuthorize(READ)
    public List<WindowEventView> events(@PathVariable Long id) {
        return windows.events(id);
    }

    @Operation(summary = "Declared qualifications of the window's applicants, by check status (PENDING by default)")
    @GetMapping("/windows/{id}/qualification-checks")
    @PreAuthorize(READ)
    public List<tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationCheckView> qualificationChecks(@PathVariable Long id,
            @RequestParam(defaultValue = "PENDING") tz.co.msingi.cims.admission.domain.ApplicantQualification.CheckStatus status) {
        return qualificationChecks.list(id, status);
    }

    @Operation(summary = "Verify a declared qualification, or record that it is not accepted (with the reason)")
    @PostMapping("/qualifications/{id}/check")
    @PreAuthorize(WRITE)
    public tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationCheckView checkQualification(@PathVariable Long id,
            @Valid @RequestBody tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationCheckRequest req) {
        return qualificationChecks.decide(id, req);
    }

    @GetMapping("/applications")
    @PreAuthorize(READ)
    public PageResponse<ApplicationSummaryView> applications(@RequestParam(required = false) Long windowId,
                                                             @RequestParam(required = false) Application.Status status,
                                                             @RequestParam(required = false) String q,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return applications.search(windowId, status, q, page, size);
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize(READ)
    public ApplicationDetailView application(@PathVariable Long id) {
        return applications.detail(id);
    }
}
