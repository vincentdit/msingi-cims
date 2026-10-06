package tz.co.msingi.cims.academic.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.IntakeRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.academic.domain.Intake;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.academic.service.AcademicDtos.AcademicYearRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.AcademicYearView;
import tz.co.msingi.cims.academic.service.AcademicDtos.DepartmentRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.DepartmentView;
import tz.co.msingi.cims.academic.service.AcademicDtos.IntakeRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.IntakeView;
import tz.co.msingi.cims.academic.service.AcademicDtos.ProgrammeRequest;
import tz.co.msingi.cims.academic.service.AcademicDtos.ProgrammeView;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AcademicService {

    private final AcademicYearRepository years;
    private final IntakeRepository intakes;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final AuditService audit;

    // ---- academic years
    @Transactional(readOnly = true)
    public List<AcademicYearView> listYears() {
        return years.findAllByOrderByStartDateDesc().stream().map(AcademicYearView::of).toList();
    }

    @Transactional
    public AcademicYearView saveYear(Long id, AcademicYearRequest req) {
        if (!req.endDate().isAfter(req.startDate())) {
            throw new BusinessRuleException("INVALID_DATES", "End date must be after start date");
        }
        AcademicYear y = id == null ? new AcademicYear() : years.findById(id).orElseThrow(() -> new NotFoundException("Academic year", id));
        if (req.current()) {
            years.clearCurrent();
        }
        y.setCode(req.code());
        y.setStartDate(req.startDate());
        y.setEndDate(req.endDate());
        y.setCurrent(req.current());
        y = years.save(y);
        audit.record(id == null ? "CREATE" : "UPDATE", "AcademicYear", y.getId(), Map.of("code", y.getCode()));
        return AcademicYearView.of(y);
    }

    // ---- intakes
    @Transactional(readOnly = true)
    public List<IntakeView> listIntakes() {
        return intakes.findAllByOrderByStartMonthAsc().stream().map(IntakeView::of).toList();
    }

    @Transactional
    public IntakeView saveIntake(Long id, IntakeRequest req) {
        Intake i = id == null ? new Intake() : intakes.findById(id).orElseThrow(() -> new NotFoundException("Intake", id));
        i.setCode(req.code());
        i.setName(req.name());
        i.setStartMonth(req.startMonth());
        i.setActive(req.active());
        i = intakes.save(i);
        audit.record(id == null ? "CREATE" : "UPDATE", "Intake", i.getId(), Map.of("code", i.getCode()));
        return IntakeView.of(i);
    }

    // ---- departments
    @Transactional(readOnly = true)
    public List<DepartmentView> listDepartments() {
        return departments.findAllByOrderByNameAsc().stream().map(DepartmentView::of).toList();
    }

    @Transactional
    public DepartmentView saveDepartment(Long id, DepartmentRequest req) {
        Department d = id == null ? new Department() : departments.findById(id).orElseThrow(() -> new NotFoundException("Department", id));
        d.setCode(req.code());
        d.setName(req.name());
        d.setActive(req.active());
        d = departments.save(d);
        audit.record(id == null ? "CREATE" : "UPDATE", "Department", d.getId(), Map.of("code", d.getCode()));
        return DepartmentView.of(d);
    }

    // ---- programmes
    @Transactional(readOnly = true)
    public List<ProgrammeView> listProgrammes() {
        return programmes.findAllByOrderByAwardFamilyAscAwardLevelAscNameAsc().stream().map(ProgrammeView::of).toList();
    }

    @Transactional(readOnly = true)
    public ProgrammeView getProgramme(Long id) {
        return ProgrammeView.of(programmes.findById(id).orElseThrow(() -> new NotFoundException("Programme", id)));
    }

    @Transactional
    public ProgrammeView saveProgramme(Long id, ProgrammeRequest req) {
        validateLevel(req.awardFamily(), req.awardLevel());
        Programme p = id == null ? new Programme() : programmes.findById(id).orElseThrow(() -> new NotFoundException("Programme", id));
        Department dept = departments.findById(req.departmentId()).orElseThrow(() -> new NotFoundException("Department", req.departmentId()));
        p.setDepartment(dept);
        p.setCode(req.code());
        p.setNactvetCode(req.nactvetCode());
        p.setName(req.name());
        p.setAwardFamily(req.awardFamily());
        p.setAwardLevel(req.awardLevel());
        p.setAwardName(req.awardName() == null || req.awardName().isBlank()
                ? Programme.defaultAwardName(req.awardFamily(), req.awardLevel()) : req.awardName());
        p.setDurationSemesters(req.durationSemesters());
        p.setAccreditedCapacity(req.accreditedCapacity());
        p.setDescription(req.description());
        p.setActive(req.active());
        p = programmes.save(p);
        audit.record(id == null ? "CREATE" : "UPDATE", "Programme", p.getId(), Map.of("code", p.getCode()));
        return ProgrammeView.of(p);
    }

    static void validateLevel(Programme.AwardFamily family, int level) {
        boolean ok = family == Programme.AwardFamily.NVA ? level >= 1 && level <= 3 : level >= 4 && level <= 8;
        if (!ok) {
            throw new BusinessRuleException("INVALID_AWARD_LEVEL",
                    family == Programme.AwardFamily.NVA ? "NVA levels are I to III (1–3)" : "NTA levels are 4 to 8");
        }
    }
}
