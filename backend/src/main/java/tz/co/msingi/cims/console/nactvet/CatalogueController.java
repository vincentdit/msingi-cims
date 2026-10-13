package tz.co.msingi.cims.console.nactvet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.CatalogueStatus;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.InstitutionDetail;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.InstitutionPage;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.SyncRun;

/** The NACTVET institution/programme catalogue in the platform console (Msingi staff only). */
@Tag(name = "Platform console")
@RestController
@RequestMapping("/api/platform/nactvet")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class CatalogueController {

    private final CatalogueService service;

    @GetMapping("/status")
    public CatalogueStatus status() {
        return service.status();
    }

    @Operation(summary = "Pull every registered institution and its programmes from NACTVET (runs in the background unless wait=true)")
    @PostMapping("/sync")
    public SyncRun sync(@RequestParam(defaultValue = "false") boolean wait) {
        return service.sync(wait);
    }

    @GetMapping("/syncs/{id}")
    public SyncRun run(@PathVariable Long id) {
        return service.run(id);
    }

    @GetMapping("/institutions")
    public InstitutionPage institutions(@RequestParam(required = false) String q, @RequestParam(required = false) String region,
                                        @RequestParam(required = false) String ownership, @RequestParam(required = false) String onboarded,
                                        @RequestParam(defaultValue = "false") boolean withdrawn,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.institutions(q, region, ownership, onboarded, withdrawn, page, size);
    }

    @GetMapping("/institutions/{id}")
    public InstitutionDetail institution(@PathVariable Long id) {
        return service.institution(id);
    }
}
