package tz.co.msingi.cims.academic.curriculum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Curricula from NACTVET's curriculum-details service (FR-ACA-06). */
@Tag(name = "Academic calendar and curricula")
@RestController
@RequestMapping("/api/academic")
@RequiredArgsConstructor
public class NactvetCurriculumController {

    private static final String PREPARE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR')";

    private final NactvetCurriculumImporter importer;

    public record ImportRequest(Long effectiveYearId, String intake) {
    }

    public record NactvetIdRequest(String nactvetProgrammeId) {
    }

    @Operation(summary = "Programmes with their NACTVET programme ID, the NTA levels asked for and the last import")
    @GetMapping("/nactvet-curricula")
    @PreAuthorize(PREPARE)
    public List<NactvetCurriculumImporter.ProgrammeRow> programmes() {
        return importer.programmes();
    }

    @PutMapping("/programmes/{id}/nactvet-programme-id")
    @PreAuthorize(PREPARE)
    public NactvetCurriculumImporter.ProgrammeRow setNactvetId(@PathVariable Long id, @RequestBody NactvetIdRequest r) {
        return importer.setNactvetId(id, r.nactvetProgrammeId());
    }

    @Operation(summary = "Get the programme's curriculum from NACTVET into its draft curriculum for the year (intake SEPT or MARCH)")
    @PostMapping("/programmes/{id}/nactvet-curriculum")
    @PreAuthorize(PREPARE)
    public NactvetCurriculumImporter.ImportResult importCurriculum(@PathVariable Long id, @RequestBody ImportRequest r) {
        return importer.importProgramme(id, r.effectiveYearId(), r.intake());
    }
}
