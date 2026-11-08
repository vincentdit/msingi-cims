package tz.co.msingi.cims.registry.nactvet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** NACTVET returns: where each return stands for a year and the data problems to fix before sending. */
@Tag(name = "NACTVET returns")
@RestController
@RequiredArgsConstructor
public class NactvetReturnsController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','EXAMINATIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')";

    private final NactvetReturnsService service;

    @Operation(summary = "The year's NACTVET returns (registration, results, graduands) and the checks before sending")
    @GetMapping("/api/nactvet-returns")
    @PreAuthorize(READ)
    public NactvetReturnsService.Returns returns(@RequestParam(required = false) Long academicYearId) {
        return service.returns(academicYearId);
    }

    @Operation(summary = "Every record behind the checks, as an Excel sheet to work through")
    @GetMapping("/api/nactvet-returns/problems")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> problems(@RequestParam(required = false) Long academicYearId) {
        NactvetReturnsService.Download d = service.problems(academicYearId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(d.content());
    }
}
