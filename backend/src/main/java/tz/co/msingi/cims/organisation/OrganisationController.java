package tz.co.msingi.cims.organisation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.organisation.OrganisationService.PositionRequest;
import tz.co.msingi.cims.organisation.OrganisationService.StructureView;
import tz.co.msingi.cims.organisation.OrganisationService.UnitRequest;
import tz.co.msingi.cims.platform.security.StaffAccess;

/** The organisation structure: every member of staff reads it; the institution administrator builds it. */
@Tag(name = "Organisation structure")
@RestController
@RequestMapping("/api/organisation")
@RequiredArgsConstructor
public class OrganisationController {

    private static final String BUILD = "hasRole('INSTITUTION_ADMIN')";

    private final OrganisationService service;

    @Operation(summary = "The organisation structure: units, who reports to whom, positions and holders")
    @GetMapping
    @PreAuthorize(StaffAccess.ANY)
    public StructureView structure() {
        return service.structure(false);
    }

    @Operation(summary = "Start an empty structure from the usual shape of a college, with the departments CIMS already has")
    @PostMapping("/template")
    @PreAuthorize(BUILD)
    public StructureView template() {
        return service.startFromTemplate();
    }

    @Operation(summary = "Add a unit")
    @PostMapping("/units")
    @PreAuthorize(BUILD)
    public StructureView createUnit(@Valid @RequestBody UnitRequest req) {
        return service.createUnit(req);
    }

    @Operation(summary = "Change a unit (also to move it under another unit)")
    @PutMapping("/units/{id}")
    @PreAuthorize(BUILD)
    public StructureView updateUnit(@PathVariable Long id, @Valid @RequestBody UnitRequest req) {
        return service.updateUnit(id, req);
    }

    @Operation(summary = "Remove a unit with its positions (it must have no units under it)")
    @DeleteMapping("/units/{id}")
    @PreAuthorize(BUILD)
    public StructureView deleteUnit(@PathVariable Long id) {
        return service.deleteUnit(id);
    }

    @Operation(summary = "Add a position to a unit")
    @PostMapping("/units/{id}/positions")
    @PreAuthorize(BUILD)
    public StructureView createPosition(@PathVariable Long id, @Valid @RequestBody PositionRequest req) {
        return service.createPosition(id, req);
    }

    @Operation(summary = "Change a position or its holder")
    @PutMapping("/positions/{id}")
    @PreAuthorize(BUILD)
    public StructureView updatePosition(@PathVariable Long id, @Valid @RequestBody PositionRequest req) {
        return service.updatePosition(id, req);
    }

    @Operation(summary = "Remove a position")
    @DeleteMapping("/positions/{id}")
    @PreAuthorize(BUILD)
    public StructureView deletePosition(@PathVariable Long id) {
        return service.deletePosition(id);
    }

    @Operation(summary = "Show the structure on the public site (publish=false takes it off)")
    @PostMapping("/publish")
    @PreAuthorize(BUILD)
    public StructureView publish(@RequestParam(defaultValue = "true") boolean publish) {
        return service.publish(publish);
    }
}
