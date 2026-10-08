package tz.co.msingi.cims.academic.curriculum;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumModuleRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumModuleView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumSummary;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CurriculumView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.ElectiveRuleRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.ModuleRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.ModuleView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.SlotView;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumModuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.ModuleRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The module catalogue and versioned programme curricula (FR-ACA-02/03). A draft is prepared, checked against the
 * programme's duration and elective rules, and approved by someone other than its author; approved versions never change.
 */
@Service
@RequiredArgsConstructor
public class CurriculumService {

    private final ModuleRepository modules;
    private final CurriculumRepository curricula;
    private final CurriculumModuleRepository curriculumModules;
    private final ProgrammeRepository programmes;
    private final AcademicYearRepository years;
    private final DepartmentRepository departments;
    private final StudentRepository students;
    private final AuditService audit;

    // ------------------------------------------------------------------ modules

    @Transactional(readOnly = true)
    public List<ModuleView> modules() {
        return modules.findAllByOrderByCodeAsc().stream().map(this::view).toList();
    }

    @Transactional
    public ModuleView createModule(ModuleRequest req) {
        String code = req.code().trim().toUpperCase();
        if (modules.findByCodeIgnoreCase(code).isPresent()) throw new BusinessRuleException("DUPLICATE_CODE", "Module " + code + " already exists");
        Module m = new Module();
        m.setCode(code);
        apply(m, req);
        modules.save(m);
        audit.record("MODULE_CREATED", "Module", m.getId(), Map.of("code", code));
        return view(m);
    }

    @Transactional
    public ModuleView updateModule(Long id, ModuleRequest req) {
        Module m = modules.findById(id).orElseThrow(() -> new NotFoundException("Module", id));
        String code = req.code().trim().toUpperCase();
        boolean inApproved = curricula.findAll().stream().filter(c -> c.getStatus() != Curriculum.Status.DRAFT)
                .anyMatch(c -> c.getModules().stream().anyMatch(cm -> cm.getModule().getId().equals(id)));
        if (inApproved && (!code.equals(m.getCode()) || m.getCredits().compareTo(req.credits()) != 0 || m.getAssessmentType() != req.assessmentType())) {
            throw new BusinessRuleException("MODULE_IN_USE",
                    "This module is in an approved curriculum: its code, credits and assessment type cannot change. Create a new module for a revised curriculum.");
        }
        if (!code.equals(m.getCode()) && modules.findByCodeIgnoreCase(code).isPresent()) {
            throw new BusinessRuleException("DUPLICATE_CODE", "Module " + code + " already exists");
        }
        m.setCode(code);
        apply(m, req);
        audit.record("MODULE_UPDATED", "Module", m.getId(), Map.of("code", code));
        return view(m);
    }

    private void apply(Module m, ModuleRequest req) {
        m.setTitle(req.title().trim());
        m.setCredits(req.credits());
        m.setNtaLevel(req.ntaLevel());
        m.setAssessmentType(req.assessmentType());
        m.setDepartment(req.departmentId() == null ? null
                : departments.findById(req.departmentId()).orElseThrow(() -> new NotFoundException("Department", req.departmentId())));
        m.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        m.setActive(req.active());
    }

    private ModuleView view(Module m) {
        return new ModuleView(m.getId(), m.getCode(), m.getTitle(), m.getCredits(), m.getNtaLevel(), m.getAssessmentType().name(),
                m.getDepartment() == null ? null : m.getDepartment().getId(), m.getDepartment() == null ? null : m.getDepartment().getName(),
                m.getDescription(), m.isActive(), m.getId() == null ? 0 : curriculumModules.countByModuleId(m.getId()));
    }

    // ------------------------------------------------------------------ curricula

    @Transactional(readOnly = true)
    public List<CurriculumSummary> curricula(Long programmeId) {
        List<Curriculum> list = programmeId == null ? curricula.findAllByOrderByProgrammeIdAscCurriculumVersionDesc()
                : curricula.findByProgrammeIdOrderByCurriculumVersionDesc(programmeId);
        return list.stream().map(c -> new CurriculumSummary(c.getId(), c.getProgramme().getId(), c.getProgramme().getCode(), c.getProgramme().getName(),
                c.getCurriculumVersion(), c.getEffectiveYear().getCode(), c.getStatus().name(), c.getModules().size(),
                c.getModules().stream().map(cm -> cm.getModule().getCredits()).reduce(BigDecimal.ZERO, BigDecimal::add), c.getApprovedAt())).toList();
    }

    @Transactional(readOnly = true)
    public CurriculumView get(Long id) {
        return view(require(id));
    }

    @Transactional
    public CurriculumView create(CurriculumRequest req) {
        Programme p = programmes.findById(req.programmeId()).orElseThrow(() -> new NotFoundException("Programme", req.programmeId()));
        if (curricula.existsByProgrammeIdAndStatus(p.getId(), Curriculum.Status.DRAFT)) {
            throw new BusinessRuleException("DRAFT_EXISTS", "There is already a draft curriculum for " + p.getCode() + ": continue that one");
        }
        Curriculum c = new Curriculum();
        c.setProgramme(p);
        c.setCurriculumVersion((short) (curricula.findByProgrammeIdOrderByCurriculumVersionDesc(p.getId()).stream()
                .mapToInt(Curriculum::getCurriculumVersion).max().orElse(0) + 1));
        c.setCreatedBy(CurrentUser.id().orElse(null));
        c.setCreatedByName(CurrentUser.name());
        apply(c, req);
        curricula.save(c);
        audit.record("CURRICULUM_CREATED", "Curriculum", c.getId(), Map.of("programme", p.getCode(), "version", c.getCurriculumVersion()));
        return view(c);
    }

    @Transactional
    public CurriculumView update(Long id, CurriculumRequest req) {
        Curriculum c = require(id);
        if (c.getStatus() != Curriculum.Status.DRAFT) throw new BusinessRuleException("NOT_A_DRAFT", "Only a draft curriculum can be changed: revise it instead");
        if (!c.getProgramme().getId().equals(req.programmeId())) throw new BusinessRuleException("WRONG_PROGRAMME", "A curriculum cannot move to another programme");
        apply(c, req);
        audit.record("CURRICULUM_UPDATED", "Curriculum", c.getId(), Map.of("modules", c.getModules().size()));
        return view(c);
    }

    /** Approval by the Deputy Principal (Academic) or administrator who did not prepare the draft, once it has no problems. */
    @Transactional
    public CurriculumView approve(Long id) {
        Curriculum c = require(id);
        if (c.getStatus() != Curriculum.Status.DRAFT) throw new BusinessRuleException("NOT_A_DRAFT", "Only a draft curriculum can be approved");
        if (c.getCreatedBy() != null && c.getCreatedBy().equals(CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "The person who prepared a curriculum cannot approve it");
        }
        List<String> problems = problems(c);
        if (!problems.isEmpty()) throw new BusinessRuleException("CURRICULUM_INCOMPLETE", String.join("; ", problems));
        c.setStatus(Curriculum.Status.APPROVED);
        c.setApprovedAt(OffsetDateTime.now());
        c.setApprovedByName(CurrentUser.name());
        audit.record("CURRICULUM_APPROVED", "Curriculum", c.getId(), Map.of("programme", c.getProgramme().getCode(), "version", c.getCurriculumVersion()));
        return view(c);
    }

    /** A new draft version starting from this one; students keep the version they started on. */
    @Transactional
    public CurriculumView revise(Long id) {
        Curriculum src = require(id);
        if (src.getStatus() == Curriculum.Status.DRAFT) throw new BusinessRuleException("IS_A_DRAFT", "This is already a draft");
        if (curricula.existsByProgrammeIdAndStatus(src.getProgramme().getId(), Curriculum.Status.DRAFT)) {
            throw new BusinessRuleException("DRAFT_EXISTS", "There is already a draft curriculum for " + src.getProgramme().getCode());
        }
        CurriculumRequest req = new CurriculumRequest(src.getProgramme().getId(), src.getEffectiveYear().getId(), src.getNotes(),
                src.getModules().stream().map(cm -> new CurriculumModuleRequest(cm.getModule().getId(), cm.getYearOfStudy(), cm.getSemesterNo(),
                        cm.getCategory())).toList(),
                src.getElectiveRules().stream().map(r -> new ElectiveRuleRequest(r.getYearOfStudy(), r.getSemesterNo(), r.getMinElectives(),
                        r.getMaxElectives())).toList());
        return create(req);
    }

    @Transactional
    public CurriculumView retire(Long id) {
        Curriculum c = require(id);
        if (c.getStatus() != Curriculum.Status.APPROVED) throw new BusinessRuleException("NOT_APPROVED", "Only an approved curriculum can be retired");
        c.setStatus(Curriculum.Status.RETIRED);
        c.setRetiredAt(OffsetDateTime.now());
        audit.record("CURRICULUM_RETIRED", "Curriculum", c.getId(), Map.of("programme", c.getProgramme().getCode(), "version", c.getCurriculumVersion()));
        return view(c);
    }

    /**
     * The curriculum a student follows: the one they started on or, the first time, the newest approved version that applies to
     * students entering in their entry year.
     */
    @Transactional(readOnly = true)
    public Optional<Curriculum> curriculumFor(Student s) {
        if (s.getCurriculumId() != null) return curricula.findById(s.getCurriculumId());
        AcademicYear entry = s.getEntryAcademicYear();
        return curricula.findByProgrammeIdAndStatus(s.getProgramme().getId(), Curriculum.Status.APPROVED).stream()
                .filter(c -> !c.getEffectiveYear().getStartDate().isAfter(entry.getStartDate()))
                .max(Comparator.comparing((Curriculum c) -> c.getEffectiveYear().getStartDate()).thenComparing(Curriculum::getCurriculumVersion));
    }

    // ------------------------------------------------------------------ editing and checks

    private void apply(Curriculum c, CurriculumRequest req) {
        AcademicYear y = years.findById(req.effectiveYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.effectiveYearId()));
        int semesters = Math.max(1, c.getProgramme().getDurationSemesters());
        Set<Long> seen = new HashSet<>();
        List<CurriculumModule> placed = new ArrayList<>();
        for (CurriculumModuleRequest r : req.modules()) {
            if (!seen.add(r.moduleId())) throw new BusinessRuleException("DUPLICATE_MODULE", "A module can appear only once in a curriculum");
            if (!slotExists(r.yearOfStudy(), r.semesterNo(), semesters)) {
                throw new BusinessRuleException("OUTSIDE_PROGRAMME", "Year " + r.yearOfStudy() + " semester " + r.semesterNo() + " is beyond the programme's "
                        + semesters + " semesters");
            }
            CurriculumModule cm = new CurriculumModule();
            cm.setCurriculum(c);
            cm.setModule(modules.findById(r.moduleId()).orElseThrow(() -> new NotFoundException("Module", r.moduleId())));
            cm.setYearOfStudy(r.yearOfStudy());
            cm.setSemesterNo(r.semesterNo());
            cm.setCategory(r.category());
            placed.add(cm);
        }
        Set<String> ruleSlots = new HashSet<>();
        List<ElectiveRule> rules = new ArrayList<>();
        for (ElectiveRuleRequest r : req.electiveRules()) {
            if (!ruleSlots.add(r.yearOfStudy() + "/" + r.semesterNo())) throw new BusinessRuleException("DUPLICATE_RULE", "One elective rule per semester");
            if (r.maxElectives() < r.minElectives()) throw new BusinessRuleException("INVALID_RULE", "The most electives cannot be fewer than the fewest");
            ElectiveRule e = new ElectiveRule();
            e.setCurriculum(c);
            e.setYearOfStudy(r.yearOfStudy());
            e.setSemesterNo(r.semesterNo());
            e.setMinElectives(r.minElectives());
            e.setMaxElectives(r.maxElectives());
            rules.add(e);
        }
        c.setEffectiveYear(y);
        c.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        c.getModules().clear();
        c.getElectiveRules().clear();
        // delete the old rows before inserting the new ones: Hibernate inserts first, which trips the unique keys
        if (c.getId() != null) curricula.flush();
        c.getModules().addAll(placed);
        c.getElectiveRules().addAll(rules);
    }

    private static boolean slotExists(int year, int semester, int programmeSemesters) {
        return semester <= 2 && (year - 1) * 2 + semester <= programmeSemesters;
    }

    /** What stops approval: empty semesters, electives without a rule, rules that cannot be met, inactive modules. */
    static List<String> problems(Curriculum c) {
        return slots(c).stream().flatMap(s -> s.problems().stream().map(p -> "Year " + s.yearOfStudy() + " semester " + s.semesterNo() + ": " + p))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    static List<SlotView> slots(Curriculum c) {
        int semesters = Math.max(1, c.getProgramme().getDurationSemesters());
        Map<String, ElectiveRule> rules = new HashMap<>();
        c.getElectiveRules().forEach(r -> rules.put(r.getYearOfStudy() + "/" + r.getSemesterNo(), r));
        List<SlotView> out = new ArrayList<>();
        for (int i = 1; i <= semesters; i++) {
            short year = (short) ((i + 1) / 2), sem = (short) (i % 2 == 1 ? 1 : 2);
            List<CurriculumModule> here = c.getModules().stream().filter(cm -> cm.getYearOfStudy() == year && cm.getSemesterNo() == sem).toList();
            List<CurriculumModule> core = here.stream().filter(cm -> cm.getCategory() == CurriculumModule.Category.CORE).toList();
            long electives = here.size() - core.size();
            ElectiveRule rule = rules.get(year + "/" + sem);
            List<String> problems = new ArrayList<>();
            if (here.isEmpty()) problems.add("no modules");
            if (electives > 0 && rule == null) problems.add("say how many of the " + electives + " electives a student takes");
            if (rule != null && rule.getMaxElectives() > electives) problems.add("up to " + rule.getMaxElectives() + " electives allowed but only " + electives + " offered");
            here.stream().filter(cm -> !cm.getModule().isActive()).forEach(cm -> problems.add(cm.getModule().getCode() + " is inactive"));
            out.add(new SlotView(year, sem, core.stream().map(cm -> cm.getModule().getCredits()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    core.size(), (int) electives, rule == null ? null : rule.getMinElectives(), rule == null ? null : rule.getMaxElectives(), problems));
        }
        return out;
    }

    private CurriculumView view(Curriculum c) {
        Long me = CurrentUser.id().orElse(null);
        List<CurriculumModuleView> mods = c.getModules().stream()
                .sorted(Comparator.comparing((CurriculumModule cm) -> cm.getYearOfStudy()).thenComparing(CurriculumModule::getSemesterNo)
                        .thenComparing(CurriculumModule::getCategory).thenComparing(cm -> cm.getModule().getCode()))
                .map(cm -> new CurriculumModuleView(cm.getId(), cm.getModule().getId(), cm.getModule().getCode(), cm.getModule().getTitle(),
                        cm.getModule().getCredits(), cm.getModule().getNtaLevel(), cm.getModule().getAssessmentType().name(), cm.getYearOfStudy(),
                        cm.getSemesterNo(), cm.getCategory().name())).toList();
        long onIt = c.getId() == null ? 0 : students.countByCurriculumId(c.getId());
        return new CurriculumView(c.getId(), c.getProgramme().getId(), c.getProgramme().getCode(), c.getProgramme().getName(),
                c.getProgramme().getDurationSemesters(), c.getCurriculumVersion(), c.getEffectiveYear().getId(), c.getEffectiveYear().getCode(),
                c.getStatus().name(), c.getNotes(), c.getCreatedByName(), c.getApprovedAt(), c.getApprovedByName(), c.getRetiredAt(),
                c.getStatus() == Curriculum.Status.DRAFT && !Objects.equals(c.getCreatedBy(), me), onIt, mods,
                c.getElectiveRules().stream().map(r -> new ElectiveRuleRequest(r.getYearOfStudy(), r.getSemesterNo(), r.getMinElectives(),
                        r.getMaxElectives())).toList(), slots(c), problems(c));
    }

    private Curriculum require(Long id) {
        return curricula.findById(id).orElseThrow(() -> new NotFoundException("Curriculum", id));
    }
}
