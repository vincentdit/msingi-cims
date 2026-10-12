package tz.co.msingi.cims.reference;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Public reference lists for forms: Tanzania's areas and the fixed profile choices. */
@Tag(name = "Reference data")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class ReferenceController {

    private final GeographyService geo;
    private final PrimarySchoolDirectory primarySchools;

    @Operation(summary = "Search Tanzania's primary schools (NECTA PSLE list) by name, code, region or council; at least 2 characters")
    @GetMapping("/schools/primary")
    public ResponseEntity<List<PrimarySchoolDirectory.School>> primarySchools(@RequestParam(defaultValue = "") String q) {
        return cached(primarySchools.search(q));
    }

    @Operation(summary = "Regions of Tanzania")
    @GetMapping("/geo/regions")
    public ResponseEntity<List<String>> regions() {
        return cached(geo.regions());
    }

    @Operation(summary = "Districts of a region")
    @GetMapping("/geo/districts")
    public ResponseEntity<List<String>> districts(@RequestParam String region) {
        return cached(geo.districts(region));
    }

    @Operation(summary = "Wards of a district")
    @GetMapping("/geo/wards")
    public ResponseEntity<List<String>> wards(@RequestParam String region, @RequestParam String district) {
        return cached(geo.wards(region, district));
    }

    @Operation(summary = "Villages / streets (vijiji / mitaa) of a ward")
    @GetMapping("/geo/streets")
    public ResponseEntity<List<String>> streets(@RequestParam String region, @RequestParam String district, @RequestParam String ward) {
        return cached(geo.streets(region, district, ward));
    }

    @Operation(summary = "Choices for disability and next-of-kin relationship")
    @GetMapping("/profile-options")
    public ResponseEntity<Map<String, List<String>>> profileOptions() {
        return cached(Map.of("disabilities", ProfileOptions.DISABILITIES, "relationships", ProfileOptions.RELATIONSHIPS));
    }

    private static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic()).body(body);
    }
}
