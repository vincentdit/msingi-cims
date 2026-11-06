package tz.co.msingi.cims.organisation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The organisation structure on the institution's public site, once published. */
@Tag(name = "Public site")
@RestController
@RequestMapping("/api/public/organisation")
@RequiredArgsConstructor
public class PublicOrganisationController {

    private final OrganisationService service;

    @Operation(summary = "The published organisation structure (404 until the institution publishes it)")
    @GetMapping
    public OrganisationService.StructureView structure() {
        return service.structure(true);
    }
}
