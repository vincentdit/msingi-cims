package tz.co.msingi.cims.academic.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.academic.service.AcademicDtos.AcademicYearRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.AcademicYearView;
import tz.co.msingi.cims.academic.service.AcademicDtos.DepartmentRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.DepartmentView;
import tz.co.msingi.cims.academic.service.AcademicDtos.IntakeRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.IntakeView;
import tz.co.msingi.cims.academic.service.AcademicDtos.ProgrammeRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.ProgrammeView;
import tz.co.msingi.cims.academic.service.AcademicService;

import java.util.List;

@Tag(name = "Academic structure")
@RestController
@RequestMapping("/api/academic")
@RequiredArgsConstructor
public class AcademicController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR','MANAGEMENT_VIEWER','EXAMINATIONS_OFFICER','HEAD_OF_DEPARTMENT')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC')";

    private final AcademicService service;

    @GetMapping("/years")
    @PreAuthorize(READ)
    public List<AcademicYearView> years() {
        return service.listYears();
    }

    @PostMapping("/years")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public AcademicYearView createYear(@Valid @RequestBody AcademicYearRequest req) {
        return service.saveYear(null, req);
    }

    @PutMapping("/years/{id}")
    @PreAuthorize(WRITE)
    public AcademicYearView updateYear(@PathVariable Long id, @Valid @RequestBody AcademicYearRequest req) {
        return service.saveYear(id, req);
    }

    @GetMapping("/intakes")
    @PreAuthorize(READ)
    public List<IntakeView> intakes() {
        return service.listIntakes();
    }

    @PostMapping("/intakes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public IntakeView createIntake(@Valid @RequestBody IntakeRequest req) {
        return service.saveIntake(null, req);
    }

    @PutMapping("/intakes/{id}")
    @PreAuthorize(WRITE)
    public IntakeView updateIntake(@PathVariable Long id, @Valid @RequestBody IntakeRequest req) {
        return service.saveIntake(id, req);
    }

    @GetMapping("/departments")
    @PreAuthorize(READ)
    public List<DepartmentView> departments() {
        return service.listDepartments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public DepartmentView createDepartment(@Valid @RequestBody DepartmentRequest req) {
        return service.saveDepartment(null, req);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize(WRITE)
    public DepartmentView updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentRequest req) {
        return service.saveDepartment(id, req);
    }

    @GetMapping("/programmes")
    @PreAuthorize(READ)
    public List<ProgrammeView> programmes() {
        return service.listProgrammes();
    }

    @GetMapping("/programmes/{id}")
    @PreAuthorize(READ)
    public ProgrammeView programme(@PathVariable Long id) {
        return service.getProgramme(id);
    }

    @PostMapping("/programmes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITE)
    public ProgrammeView createProgramme(@Valid @RequestBody ProgrammeRequest req) {
        return service.saveProgramme(null, req);
    }

    @PutMapping("/programmes/{id}")
    @PreAuthorize(WRITE)
    public ProgrammeView updateProgramme(@PathVariable Long id, @Valid @RequestBody ProgrammeRequest req) {
        return service.saveProgramme(id, req);
    }
}
