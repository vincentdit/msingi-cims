package tz.co.msingi.cims.console.institutions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.AdminRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.DomainRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.InstitutionDetail;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.InstitutionRow;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.IssuedAdmin;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.OnboardRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.Overview;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.SmsCreditRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.StatusRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.UpdateRequest;

import java.util.List;

/** The institutions on the platform: onboarding, details, domains, status, administrators and SMS credit (Msingi staff only). */
@Tag(name = "Platform console")
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService service;

    @GetMapping("/overview")
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/institutions")
    public List<InstitutionRow> list() {
        return service.list();
    }

    @GetMapping("/institutions/{id}")
    public InstitutionDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @Operation(summary = "Onboard an institution with its domains and first administrator (temporary password shown once)")
    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedAdmin onboard(@Valid @RequestBody OnboardRequest req) {
        return service.onboard(req);
    }

    @PutMapping("/institutions/{id}")
    public InstitutionDetail update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/institutions/{id}/domains")
    public InstitutionDetail addDomain(@PathVariable Long id, @Valid @RequestBody DomainRequest req) {
        return service.addDomain(id, req);
    }

    @DeleteMapping("/institutions/{id}/domains/{domainId}")
    public InstitutionDetail removeDomain(@PathVariable Long id, @PathVariable Long domainId) {
        return service.removeDomain(id, domainId);
    }

    @Operation(summary = "Suspend an institution: its portals answer 'temporarily unavailable'; data is kept")
    @PostMapping("/institutions/{id}/suspend")
    public InstitutionDetail suspend(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        return service.suspend(id, req.reason());
    }

    @PostMapping("/institutions/{id}/reactivate")
    public InstitutionDetail reactivate(@PathVariable Long id, @Valid @RequestBody(required = false) StatusRequest req) {
        return service.reactivate(id, req == null ? null : req.reason());
    }

    @PostMapping("/institutions/{id}/administrators")
    public IssuedAdmin addAdministrator(@PathVariable Long id, @Valid @RequestBody AdminRequest req) {
        return service.addAdministrator(id, req);
    }

    @Operation(summary = "Issue a temporary password to one of the institution's administrators (support)")
    @PostMapping("/institutions/{id}/administrators/{userId}/reset-password")
    public IssuedAdmin resetAdministrator(@PathVariable Long id, @PathVariable Long userId) {
        return service.resetAdministratorPassword(id, userId);
    }

    @Operation(summary = "Link the institution to its NACTVET catalogue entry and import its programmes")
    @PostMapping("/institutions/{id}/nactvet")
    public InstitutionDtos.NactvetImport importFromNactvet(@PathVariable Long id,
                                                         @RequestBody tz.co.msingi.cims.console.nactvet.CatalogueDtos.LinkRequest req) {
        return service.importFromNactvet(id, req.nactvetInstitutionId(), req.programmeIds());
    }

    @DeleteMapping("/institutions/{id}/nactvet")
    public InstitutionDetail unlinkNactvet(@PathVariable Long id) {
        return service.unlinkNactvet(id);
    }

    @PostMapping("/institutions/{id}/sms-credit")
    public InstitutionDetail creditSms(@PathVariable Long id, @Valid @RequestBody SmsCreditRequest req) {
        return service.creditSms(id, req);
    }
}
