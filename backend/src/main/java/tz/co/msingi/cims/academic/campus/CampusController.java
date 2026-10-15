package tz.co.msingi.cims.academic.campus;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.academic.campus.CampusService.CampusRequest;
import tz.co.msingi.cims.academic.campus.CampusService.CampusView;
import tz.co.msingi.cims.academic.campus.CampusService.NamedRequest;
import tz.co.msingi.cims.academic.campus.CampusService.NamedView;
import tz.co.msingi.cims.academic.campus.CampusService.ProgrammeCampusesRequest;

import java.util.List;
import java.util.Map;

/** Campuses, faculties, programme campuses and student categories. */
@Tag(name = "Academic structure — campuses")
@RestController
@RequestMapping("/api/academic")
@RequiredArgsConstructor
public class CampusController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR','MANAGEMENT_VIEWER','EXAMINATIONS_OFFICER','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR','LECTURER','ASSESSOR','BURSAR','ACCOUNTANT','HOSTEL_WARDEN','IPT_COORDINATOR','AUDITOR')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String CATEGORY_WRITE = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','REGISTRAR')";

    private final CampusService service;

    @GetMapping("/campuses")
    @PreAuthorize(READ)
    public List<CampusView> campuses() {
        return service.campuses();
    }

    @PostMapping("/campuses")
    @PreAuthorize(WRITE)
    public CampusView createCampus(@Valid @RequestBody CampusRequest req) {
        return service.saveCampus(null, req);
    }

    @PutMapping("/campuses/{id}")
    @PreAuthorize(WRITE)
    public CampusView updateCampus(@PathVariable Long id, @Valid @RequestBody CampusRequest req) {
        return service.saveCampus(id, req);
    }

    @GetMapping("/programme-campuses")
    @PreAuthorize(READ)
    public Map<Long, List<Long>> programmeCampuses() {
        return service.programmeCampusMap();
    }

    @PutMapping("/programmes/{id}/campuses")
    @PreAuthorize(WRITE)
    public List<Long> setProgrammeCampuses(@PathVariable Long id, @RequestBody ProgrammeCampusesRequest req) {
        return service.setProgrammeCampuses(id, req.campusIds());
    }

    @GetMapping("/faculties")
    @PreAuthorize(READ)
    public List<NamedView> faculties() {
        return service.faculties();
    }

    @PostMapping("/faculties")
    @PreAuthorize(WRITE)
    public NamedView createFaculty(@Valid @RequestBody NamedRequest req) {
        return service.saveFaculty(null, req);
    }

    @PutMapping("/faculties/{id}")
    @PreAuthorize(WRITE)
    public NamedView updateFaculty(@PathVariable Long id, @Valid @RequestBody NamedRequest req) {
        return service.saveFaculty(id, req);
    }

    @GetMapping("/student-categories")
    @PreAuthorize(READ)
    public List<NamedView> categories() {
        return service.categories();
    }

    @PostMapping("/student-categories")
    @PreAuthorize(CATEGORY_WRITE)
    public NamedView createCategory(@Valid @RequestBody NamedRequest req) {
        return service.saveCategory(null, req);
    }

    @PutMapping("/student-categories/{id}")
    @PreAuthorize(CATEGORY_WRITE)
    public NamedView updateCategory(@PathVariable Long id, @Valid @RequestBody NamedRequest req) {
        return service.saveCategory(id, req);
    }
}
