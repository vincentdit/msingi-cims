package tz.co.msingi.cims.platform.branding;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.branding.InstitutionProfileService.Profile;

/** The institution's contacts and social media pages, kept by its administrator. */
@Tag(name = "Institution profile")
@RestController
@RequestMapping("/api/admin/institution/profile")
@PreAuthorize("hasRole('INSTITUTION_ADMIN')")
@RequiredArgsConstructor
public class InstitutionProfileController {

    private final InstitutionProfileService service;

    @GetMapping
    public Profile get() {
        return service.get();
    }

    @PutMapping
    public Profile save(@RequestBody Profile p) {
        return service.save(p);
    }
}
