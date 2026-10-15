package tz.co.msingi.cims.registry.nin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Registry — National Identification Number")
@RestController
@RequiredArgsConstructor
public class NinController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','ADMISSIONS_OFFICER')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";

    private final NinService service;

    public record NinRequest(@NotBlank @Size(max = 30) String nin) {
    }

    @Operation(summary = "The student's own NIN: last four digits and NIDA status")
    @GetMapping("/api/student/nin")
    @PreAuthorize("hasRole('STUDENT')")
    public NinService.NinView mine() {
        return service.mine();
    }

    @PutMapping("/api/student/nin")
    @PreAuthorize("hasRole('STUDENT')")
    public NinService.NinView saveMine(@Valid @RequestBody NinRequest req) {
        return service.saveMine(req.nin());
    }

    @GetMapping("/api/registry/students/{id}/nin")
    @PreAuthorize(READ)
    public NinService.NinView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/api/registry/students/{id}/nin")
    @PreAuthorize(WRITE)
    public NinService.NinView set(@PathVariable Long id, @Valid @RequestBody NinRequest req) {
        return service.set(id, req.nin());
    }

    @PostMapping("/api/registry/students/{id}/nin/verify")
    @PreAuthorize(WRITE)
    public NinService.NinView verify(@PathVariable Long id) {
        return service.verifyAgain(id);
    }
}
