package tz.co.msingi.cims.exams.competence;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRegistrationQueries;
import tz.co.msingi.cims.exams.RegulationService;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AwardClass;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AwardLine;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.AwardList;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.Cell;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.Entry;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.MyTask;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.MyUnit;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.Permissions;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.Person;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.SetupResult;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.TaskDto;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.TaskView;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.UnitSummary;
import tz.co.msingi.cims.exams.competence.CompetenceDtos.UnitView;
import tz.co.msingi.cims.exams.competence.CompetenceRepositories.CompetenceAssessmentRepository;
import tz.co.msingi.cims.exams.competence.CompetenceRepositories.CompetenceUnitRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NVA competence-based assessment (BP-09, FR-EXM-09, BR-16): competence units set up from approved registrations, tasks and their
 * evidence set by the Head of Department, assessors allocated, each trainee's outcome per task recorded with date, assessor and
 * evidence reference (tablet-friendly), re-assessment of not-yet-competent tasks up to the institution's limit, submission and
 * verification by the Head of Department (verified attempts are locked), and award eligibility confirmed per class by the
 * Examinations Officer with an Excel list for NACTVET.
 */
@Service
@RequiredArgsConstructor
public class CompetenceService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final SemesterRegistration.Status APPROVED = SemesterRegistration.Status.APPROVED;
    public static final String COMPETENT = "COMPETENT", NYC = "NOT_YET_COMPETENT", INCOMPLETE = "INCOMPLETE";

    private final CompetenceUnitRepository units;
    private final CompetenceAssessmentRepository assessments;
    private final SemesterRepository semesters;
    private final ProgrammeRepository programmes;
    private final ExamRegistrationQueries registrations;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final RegulationService regulations;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ set-up and allocation (BP-09 step 1)

    /**
     * Creates a competence unit for every module taken in the semester that is assessed by competence: modules marked as such, and
     * every module whose students are mostly on an NVA programme. Each starts with a practical and a theory task.
     */
    @Transactional
    public SetupResult setup(Long semesterId) {
        access.require("set up competence units", ExamAccess.ALLOCATORS);
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        int created = 0;
        List<String> skipped = new ArrayList<>();
        for (Long moduleId : registrations.modulesTaken(semesterId, APPROVED)) {
            if (units.findBySemesterIdAndModuleId(semesterId, moduleId).isPresent()) continue;
            List<ModuleRegistration> regs = registrations.students(semesterId, moduleId, APPROVED);
            if (regs.isEmpty()) continue;
            Module m = regs.get(0).getModule();
            Programme p = regs.stream().map(r -> r.getRegistration().getStudent().getProgramme())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            boolean competence = m.getAssessmentType() == Module.AssessmentType.COMPETENCE || p.getAwardFamily() == Programme.AwardFamily.NVA;
            if (!competence) continue;
            if (!wide() && !access.moderates(m)) {
                skipped.add(m.getCode() + ": outside your department");
                continue;
            }
            CompetenceUnit u = new CompetenceUnit();
            u.setSemester(sem);
            u.setModule(m);
            u.getTasks().add(task(u, "Practical task", CompetenceTask.Kind.PRACTICAL, "Observation checklist or photo of the finished work", 0));
            u.getTasks().add(task(u, "Underpinning knowledge (theory)", CompetenceTask.Kind.THEORY, "Marked written or oral test", 1));
            units.save(u);
            created++;
        }
        audit.record("COMPETENCE_UNITS_SET_UP", "Semester", semesterId, Map.of("created", created, "skipped", skipped.size()));
        return new SetupResult(created, skipped);
    }

    private static CompetenceTask task(CompetenceUnit u, String name, CompetenceTask.Kind kind, String evidence, int order) {
        CompetenceTask t = new CompetenceTask();
        t.setUnit(u);
        t.setName(name);
        t.setKind(kind);
        t.setEvidenceRequired(evidence);
        t.setRequired(true);
        t.setSortOrder((short) order);
        return t;
    }

    @Transactional
    public UnitView saveTasks(Long id, List<TaskDto> plan) {
        CompetenceUnit u = require(id);
        if (!canEditTasks(u)) throw new AccessDeniedException("Only the Head of Department sets the tasks, before the unit is submitted");
        Map<Long, CompetenceTask> existing = u.getTasks().stream().collect(Collectors.toMap(CompetenceTask::getId, Function.identity()));
        Set<Long> kept = plan.stream().map(TaskDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (Long k : kept) if (!existing.containsKey(k)) throw new BusinessRuleException("UNKNOWN_TASK", "That task is not part of this unit");
        for (CompetenceTask t : existing.values()) {
            if (!kept.contains(t.getId()) && assessments.countByTaskId(t.getId()) > 0) {
                throw new BusinessRuleException("TASK_HAS_ASSESSMENTS", t.getName() + " already has assessments, so it cannot be removed.");
            }
        }
        if (plan.stream().noneMatch(t -> t.required() == null || t.required())) {
            throw new BusinessRuleException("NO_REQUIRED_TASK", "At least one task must be required for competence");
        }
        u.getTasks().removeIf(t -> !kept.contains(t.getId()));
        short order = 0;
        for (TaskDto d : plan) {
            CompetenceTask t = d.id() == null ? null : existing.get(d.id());
            if (t == null) {
                t = new CompetenceTask();
                t.setUnit(u);
                u.getTasks().add(t);
            }
            t.setName(d.name().trim());
            t.setKind(d.kind());
            t.setEvidenceRequired(blank(d.evidenceRequired()));
            t.setRequired(d.required() == null || d.required());
            t.setSortOrder(order++);
        }
        units.saveAndFlush(u);
        audit.record("COMPETENCE_TASKS_SET", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(), "tasks", plan.size()));
        return view(u);
    }

    @Transactional
    public UnitView assign(Long id, List<Long> userIds) {
        CompetenceUnit u = require(id);
        if (!canAssign(u)) throw new AccessDeniedException("Only the Examinations Officer or the module's Head of Department allocates assessors");
        Set<Long> wanted = new HashSet<>(userIds);
        List<UserAccount> found = users.findAllById(wanted);
        if (found.size() != wanted.size() || found.stream().anyMatch(x -> x.getUserType() != UserAccount.UserType.STAFF)) {
            throw new BusinessRuleException("UNKNOWN_STAFF", "Choose staff members of this institution");
        }
        u.getAssessors().removeIf(a -> !wanted.contains(a.getUserId()));
        Set<Long> have = u.getAssessors().stream().map(CompetenceAssessor::getUserId).collect(Collectors.toSet());
        for (Long uid : wanted) {
            if (have.contains(uid)) continue;
            CompetenceAssessor a = new CompetenceAssessor();
            a.setUnit(u);
            a.setUserId(uid);
            u.getAssessors().add(a);
        }
        units.saveAndFlush(u);
        audit.record("ASSESSORS_ALLOCATED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(),
                "staff", found.stream().map(UserAccount::getFullName).sorted().toList()));
        return view(u);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<UnitSummary> list(Long semesterId, boolean mineOnly) {
        Long me = CurrentUser.requireId();
        List<CompetenceUnit> all = units.findBySemesterIdOrderByIdAsc(semesterId);
        Map<Long, String> names = names(all);
        List<UnitSummary> out = new ArrayList<>();
        for (CompetenceUnit u : all.stream().sorted(Comparator.comparing((CompetenceUnit x) -> x.getModule().getCode())).toList()) {
            boolean mine = isAssessor(u, me);
            if (!mine && (mineOnly || !access.follows(u.getModule()))) continue;
            List<ModuleRegistration> regs = classList(u);
            Map<Long, String> outcomes = outcomes(u, regs, assessments.findByUnit(u.getId()));
            out.add(new UnitSummary(u.getId(), semesterId, u.getModule().getCode(), u.getModule().getTitle(), dept(u), u.getStatus().name(), regs.size(),
                    u.getTasks().size(), u.getAssessors().stream().map(a -> names.getOrDefault(a.getUserId(), "—")).sorted().toList(),
                    count(outcomes, COMPETENT), count(outcomes, NYC), count(outcomes, INCOMPLETE), mine, u.getReturnedNote()));
        }
        return out;
    }

    /** Units allocated to the signed-in assessor in a semester (for My teaching). */
    @Transactional(readOnly = true)
    public List<UnitSummary> mine(Long semesterId) {
        return list(semesterId, true);
    }

    @Transactional(readOnly = true)
    public UnitView get(Long id) {
        CompetenceUnit u = require(id);
        if (!isAssessor(u, CurrentUser.requireId()) && !access.follows(u.getModule())) throw new AccessDeniedException("Not your unit");
        return view(u);
    }

    // ------------------------------------------------------------------ recording (BP-09 step 2)

    /**
     * Records outcomes. An attempt not yet verified is corrected in place; a not-yet-competent task is re-assessed (a new attempt) when
     * asked, or always once the earlier attempt is verified, up to the institution's limit; a verified competent task does not change.
     */
    @Transactional
    public UnitView record(Long id, List<Entry> entries) {
        CompetenceUnit u = require(id);
        Long me = CurrentUser.requireId();
        if (!isAssessor(u, me)) throw new AccessDeniedException("Only the unit's assessors record its outcomes");
        if (u.getStatus() != CompetenceUnit.Status.DRAFT) {
            throw new BusinessRuleException("UNIT_SUBMITTED", "This unit has been submitted; its outcomes can no longer change");
        }
        int max = regulations.settings().getNvaMaxAttempts();
        Map<Long, ModuleRegistration> regs = classList(u).stream().collect(Collectors.toMap(ModuleRegistration::getId, Function.identity()));
        Map<Long, CompetenceTask> tasks = u.getTasks().stream().collect(Collectors.toMap(CompetenceTask::getId, Function.identity()));
        Map<String, List<CompetenceAssessment>> history = assessments.findByUnit(u.getId()).stream()
                .collect(Collectors.groupingBy(a -> key(a.getTask().getId(), a.getModuleRegistrationId())));
        LocalDate today = LocalDate.now(ZONE);
        int created = 0, updated = 0;
        for (Entry e : entries) {
            ModuleRegistration m = regs.get(e.moduleRegistrationId());
            if (m == null) throw new BusinessRuleException("NOT_IN_CLASS", "A trainee in this list is no longer registered for the unit. Reload and try again.");
            CompetenceTask t = tasks.get(e.taskId());
            if (t == null) throw new BusinessRuleException("UNKNOWN_TASK", "That task is not part of this unit");
            if (e.assessedOn().isAfter(today)) throw new BusinessRuleException("FUTURE_DATE", "The assessment date cannot be in the future");
            if (e.assessedOn().isBefore(today.minusDays(365))) throw new BusinessRuleException("INVALID_DATE", "That assessment date is too far in the past");
            List<CompetenceAssessment> prior = history.getOrDefault(key(t.getId(), m.getId()), List.of());
            CompetenceAssessment latest = prior.stream().max(Comparator.comparingInt(CompetenceAssessment::getAttempt)).orElse(null);
            CompetenceAssessment a;
            if (latest == null) {
                a = fresh(t, m, 1);
                created++;
            } else if (latest.getAttempt() >= 1 && latest.isVerified() && latest.getOutcome() == CompetenceAssessment.Outcome.COMPETENT) {
                throw new BusinessRuleException("ALREADY_COMPETENT", m.getRegistration().getStudent().getRegistrationNo() + " is already verified competent in "
                        + t.getName());
            } else if (latest.getOutcome() == CompetenceAssessment.Outcome.NOT_YET_COMPETENT && (latest.isVerified() || Boolean.TRUE.equals(e.reassess()))) {
                if (latest.getAttempt() >= max) {
                    throw new BusinessRuleException("NO_ATTEMPTS_LEFT", m.getRegistration().getStudent().getRegistrationNo() + " has used all " + max
                            + " attempts at " + t.getName() + ". Refer the trainee to the Examinations Officer.");
                }
                a = fresh(t, m, latest.getAttempt() + 1);
                created++;
            } else if (latest.isVerified()) {
                throw new BusinessRuleException("VERIFIED", "That attempt has been verified and no longer changes");
            } else {
                a = latest;
                updated++;
            }
            a.setOutcome(e.outcome());
            a.setPracticalScore(e.practicalScore());
            a.setTheoryScore(e.theoryScore());
            a.setAssessedOn(e.assessedOn());
            a.setEvidenceRef(e.evidenceRef().trim());
            a.setComment(blank(e.comment()));
            a.setAssessorId(me);
            a.setAssessorName(CurrentUser.name());
            a.setRecordedAt(OffsetDateTime.now());
            assessments.save(a);
            if (a != latest) history.computeIfAbsent(key(t.getId(), m.getId()), k -> new ArrayList<>()).add(a);
        }
        assessments.flush();
        audit.record("COMPETENCE_RECORDED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(), "new", created, "corrected", updated));
        return view(u);
    }

    private static CompetenceAssessment fresh(CompetenceTask t, ModuleRegistration m, int attempt) {
        CompetenceAssessment a = new CompetenceAssessment();
        a.setTask(t);
        a.setModuleRegistrationId(m.getId());
        a.setStudentId(m.getStudentId());
        a.setAttempt((short) attempt);
        return a;
    }

    // ------------------------------------------------------------------ submission and verification (BP-09 step 3)

    @Transactional
    public UnitView submit(Long id) {
        CompetenceUnit u = require(id);
        if (!isAssessor(u, CurrentUser.requireId())) throw new AccessDeniedException("Only the unit's assessors submit it");
        if (u.getStatus() != CompetenceUnit.Status.DRAFT) throw new BusinessRuleException("UNIT_SUBMITTED", "This unit has already been submitted");
        List<ModuleRegistration> regs = classList(u);
        Map<Long, String> outcomes = outcomes(u, regs, assessments.findByUnit(u.getId()));
        long missing = outcomes.values().stream().filter(INCOMPLETE::equals).count();
        if (missing > 0) {
            throw new BusinessRuleException("INCOMPLETE", missing + (missing == 1 ? " trainee has" : " trainees have")
                    + " not been assessed on every required task. Record their outcomes first.");
        }
        u.setStatus(CompetenceUnit.Status.SUBMITTED);
        u.setSubmittedByName(CurrentUser.name());
        u.setSubmittedAt(OffsetDateTime.now());
        units.saveAndFlush(u);
        audit.record("COMPETENCE_SUBMITTED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(), "trainees", regs.size(),
                "competent", count(outcomes, COMPETENT)));
        return view(u);
    }

    /** The Head of Department verifies the outcomes: every attempt is locked, and trainees not yet competent are told by SMS. */
    @Transactional
    public UnitView verify(Long id) {
        CompetenceUnit u = require(id);
        if (!canModerate(u)) throw new AccessDeniedException("Only the module's Head of Department verifies competence outcomes");
        if (u.getStatus() != CompetenceUnit.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "Only a submitted unit can be verified");
        List<CompetenceAssessment> all = assessments.findByUnit(u.getId());
        all.forEach(a -> a.setVerified(true));
        assessments.saveAll(all);
        u.setStatus(CompetenceUnit.Status.VERIFIED);
        u.setVerifiedByName(CurrentUser.name());
        u.setVerifiedAt(OffsetDateTime.now());
        u.setReturnedNote(null);
        u.setReturnedByName(null);
        u.setReturnedAt(null);
        units.saveAndFlush(u);
        List<ModuleRegistration> regs = classList(u);
        Map<Long, String> outcomes = outcomes(u, regs, all);
        for (ModuleRegistration m : regs) {
            if (NYC.equals(outcomes.get(m.getId()))) {
                messaging.event("COMPETENCE_NYC", m.getRegistration().getStudent().getPhone(), Map.of("module", u.getModule().getCode()));
            }
        }
        audit.record("COMPETENCE_VERIFIED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(),
                "competent", count(outcomes, COMPETENT), "notYetCompetent", count(outcomes, NYC)));
        return view(u);
    }

    @Transactional
    public UnitView returnUnit(Long id, String note) {
        CompetenceUnit u = require(id);
        if (!canModerate(u)) throw new AccessDeniedException("Only the module's Head of Department returns competence outcomes");
        if (u.getStatus() != CompetenceUnit.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "Only a submitted unit can be returned");
        u.setStatus(CompetenceUnit.Status.DRAFT);
        u.setReturnedNote(note.trim());
        u.setReturnedByName(CurrentUser.name());
        u.setReturnedAt(OffsetDateTime.now());
        units.saveAndFlush(u);
        audit.record("COMPETENCE_RETURNED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode(), "note", note.trim()));
        return view(u);
    }

    /** The Examinations Officer reopens a verified unit so not-yet-competent trainees can be re-assessed (BP-09 step 4). */
    @Transactional
    public UnitView reopen(Long id) {
        CompetenceUnit u = require(id);
        access.require("reopen a unit for re-assessment", ExamAccess.EXAMS_OFFICE);
        if (u.getStatus() != CompetenceUnit.Status.VERIFIED) throw new BusinessRuleException("NOT_VERIFIED", "Only a verified unit is reopened for re-assessment");
        u.setStatus(CompetenceUnit.Status.DRAFT);
        u.setSubmittedAt(null);
        u.setSubmittedByName(null);
        units.saveAndFlush(u);
        audit.record("COMPETENCE_REOPENED", "CompetenceUnit", u.getId(), Map.of("module", u.getModule().getCode()));
        return view(u);
    }

    // ------------------------------------------------------------------ award eligibility (BP-09 step 4)

    /** NVA classes with approved registrations in the semester, with how far their units are verified and any confirmed list. */
    @Transactional(readOnly = true)
    public List<AwardClass> awardClasses(Long semesterId) {
        access.require("see award eligibility", ExamAccess.EXAMS_OFFICE[0], ExamAccess.EXAMS_OFFICE[1], Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER);
        List<AwardClass> out = new ArrayList<>();
        for (Object[] c : registrations.classes(semesterId, APPROVED)) {
            Programme p = programmes.findById((Long) c[0]).orElse(null);
            if (p == null || p.getAwardFamily() != Programme.AwardFamily.NVA) continue;
            short year = ((Number) c[1]).shortValue();
            Map<String, Object> st = jdbc.queryForMap("""
                    select count(distinct u.id) filter (where u.status = 'VERIFIED') as verified, count(distinct u.id) as total
                    from competence_units u where u.semester_id = ? and u.module_id in (
                      select mr.module_id from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                      join students s on s.id = sr.student_id where sr.semester_id = ? and sr.status = 'APPROVED' and s.programme_id = ? and sr.year_of_study = ?)""",
                    semesterId, semesterId, p.getId(), year);
            Map<String, Object> list = jdbc.queryForList("select id, confirmed_at, confirmed_by_name, eligible from nva_award_lists where semester_id = ? and programme_id = ? and year_of_study = ?",
                    semesterId, p.getId(), year).stream().findFirst().orElse(null);
            out.add(new AwardClass(p.getId(), p.getCode(), p.getName(), p.getAwardLevel(), year, ((Number) c[2]).intValue(),
                    ((Number) st.get("verified")).intValue(), ((Number) st.get("total")).intValue(),
                    list == null ? null : ((Number) list.get("id")).longValue(), list == null ? null : odt(list.get("confirmed_at")),
                    list == null ? null : (String) list.get("confirmed_by_name"), list == null ? null : ((Number) list.get("eligible")).intValue()));
        }
        out.sort(Comparator.comparing(AwardClass::programmeCode).thenComparing(AwardClass::yearOfStudy));
        return out;
    }

    /**
     * A class's award eligibility: each trainee is eligible when every unit they have registered for up to this semester is verified
     * and competent.
     */
    @Transactional(readOnly = true)
    public AwardList awardList(Long semesterId, Long programmeId, short year) {
        access.require("see award eligibility", ExamAccess.EXAMS_OFFICE[0], ExamAccess.EXAMS_OFFICE[1], Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER);
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        Programme p = programmes.findById(programmeId).orElseThrow(() -> new NotFoundException("Programme", programmeId));
        if (p.getAwardFamily() != Programme.AwardFamily.NVA) throw new BusinessRuleException("NOT_NVA", p.getCode() + " is not an NVA programme");
        List<SemesterRegistration> klass = registrations.classOf(semesterId, programmeId, year, APPROVED);
        Set<Long> studentIds = klass.stream().map(r -> r.getStudent().getId()).collect(Collectors.toSet());
        // every module each trainee has registered for (approved), up to this semester
        Map<Long, Map<Long, String>> registered = new HashMap<>();
        if (!studentIds.isEmpty()) {
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                    select sr.student_id, m.id, m.code from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                    join semesters se on se.id = sr.semester_id join modules m on m.id = mr.module_id
                    where sr.status = 'APPROVED' and se.start_date <= :start and sr.student_id in (:ids)""",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("start", sem.getStartDate()).addValue("ids", studentIds), rs -> {
                registered.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getLong(2), rs.getString(3));
            });
        }
        Map<Long, Map<Long, String>> verifiedOutcome = verifiedOutcomes(studentIds);
        List<AwardLine> lines = klass.stream().map(r -> {
            Student s = r.getStudent();
            Map<Long, String> mods = registered.getOrDefault(s.getId(), Map.of());
            Map<Long, String> got = verifiedOutcome.getOrDefault(s.getId(), Map.of());
            List<String> outstanding = mods.entrySet().stream().filter(en -> !COMPETENT.equals(got.get(en.getKey())))
                    .map(en -> en.getValue() + (NYC.equals(got.get(en.getKey())) ? " (not yet competent)" : " (not verified)")).sorted().toList();
            int competent = mods.size() - outstanding.size();
            return new AwardLine(s.getId(), s.getRegistrationNo(), s.fullName(), s.getSex(), competent, mods.size(), !mods.isEmpty() && outstanding.isEmpty(),
                    outstanding);
        }).sorted(Comparator.comparing(AwardLine::registrationNo)).toList();
        List<String> notVerified = jdbc.queryForList("""
                select distinct m.code from competence_units u join modules m on m.id = u.module_id where u.semester_id = ? and u.status <> 'VERIFIED'
                and u.module_id in (select mr.module_id from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                  join students s on s.id = sr.student_id where sr.semester_id = ? and sr.status = 'APPROVED' and s.programme_id = ? and sr.year_of_study = ?)
                order by 1""", String.class, semesterId, semesterId, programmeId, year);
        Map<String, Object> list = jdbc.queryForList("select id, confirmed_at, confirmed_by_name from nva_award_lists where semester_id = ? and programme_id = ? and year_of_study = ?",
                semesterId, programmeId, year).stream().findFirst().orElse(null);
        return new AwardList(semesterId, sem.getName(), p.getId(), p.getCode(), p.getName(), p.getAwardName(), year, lines,
                (int) lines.stream().filter(AwardLine::eligible).count(), list == null ? null : ((Number) list.get("id")).longValue(),
                list == null ? null : odt(list.get("confirmed_at")), list == null ? null : (String) list.get("confirmed_by_name"),
                notVerified, access.has(ExamAccess.EXAMS_OFFICE) && notVerified.isEmpty() && !lines.isEmpty());
    }

    @Transactional
    public AwardList confirm(Long semesterId, Long programmeId, short year) {
        access.require("confirm award eligibility", ExamAccess.EXAMS_OFFICE);
        AwardList l = awardList(semesterId, programmeId, year);
        if (!l.unitsNotVerified().isEmpty()) {
            throw new BusinessRuleException("UNITS_NOT_VERIFIED", "Units not yet verified by the Head of Department: " + String.join(", ", l.unitsNotVerified()));
        }
        if (l.lines().isEmpty()) throw new BusinessRuleException("NO_TRAINEES", "No trainees are registered in this class");
        jdbc.update("delete from nva_award_lists where semester_id = ? and programme_id = ? and year_of_study = ?", semesterId, programmeId, year);
        Long listId = jdbc.queryForObject("""
                insert into nva_award_lists (tenant_id, semester_id, programme_id, year_of_study, students, eligible, confirmed_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""", Long.class, semesterId, programmeId, year, l.lines().size(), l.eligible(),
                CurrentUser.name());
        for (AwardLine a : l.lines()) {
            jdbc.update("""
                    insert into nva_award_list_lines (tenant_id, list_id, student_id, units_competent, units_required, eligible, outstanding)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?)""", listId, a.studentId(), a.unitsCompetent(), a.unitsRequired(), a.eligible(),
                    a.outstanding().isEmpty() ? null : truncate(String.join("; ", a.outstanding()), 1000));
        }
        audit.record("NVA_AWARD_ELIGIBILITY_CONFIRMED", "Programme", programmeId, Map.of("programme", l.programmeCode(), "year", year,
                "semester", l.semesterName(), "trainees", l.lines().size(), "eligible", l.eligible()));
        return awardList(semesterId, programmeId, year);
    }

    /** The award eligibility list as Excel, for NACTVET. */
    @Transactional
    public byte[] awardExcel(Long semesterId, Long programmeId, short year) {
        AwardList l = awardList(semesterId, programmeId, year);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Award eligibility");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sh.createRow(0).createCell(0).setCellValue(l.programmeCode() + " " + l.programmeName() + " · year " + l.yearOfStudy() + " · " + l.semesterName());
            sh.createRow(1).createCell(0).setCellValue((l.award() == null ? "" : l.award() + " · ") + (l.confirmedAt() == null ? "Not yet confirmed"
                    : "Confirmed by " + l.confirmedByName() + " on " + l.confirmedAt().atZoneSameInstant(ZONE).toLocalDate()));
            String[] cols = {"No.", "Registration no.", "Name", "Sex", "Units competent", "Units registered", "Eligible for the award", "Outstanding"};
            Row h = sh.createRow(3);
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i]);
                h.getCell(i).setCellStyle(head);
            }
            int ri = 4, no = 1;
            for (AwardLine a : l.lines()) {
                Row r = sh.createRow(ri++);
                r.createCell(0).setCellValue(no++);
                r.createCell(1).setCellValue(a.registrationNo());
                r.createCell(2).setCellValue(a.name());
                r.createCell(3).setCellValue(a.sex() == null ? "" : a.sex().substring(0, 1));
                r.createCell(4).setCellValue(a.unitsCompetent());
                r.createCell(5).setCellValue(a.unitsRequired());
                r.createCell(6).setCellValue(a.eligible() ? "Yes" : "No");
                r.createCell(7).setCellValue(String.join("; ", a.outstanding()));
            }
            int[] widths = {6, 22, 30, 6, 14, 14, 18, 60};
            for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
            wb.write(out);
            audit.record("NVA_AWARD_LIST_EXPORTED", "Programme", programmeId, Map.of("programme", l.programmeCode(), "year", year, "trainees", l.lines().size()));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ student portal

    /** The trainee's verified competence outcomes, unit by unit. */
    @Transactional(readOnly = true)
    public List<MyUnit> myUnits() {
        Student me = students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        List<CompetenceAssessment> all = assessments.findByStudents(List.of(me.getId())).stream().filter(CompetenceAssessment::isVerified).toList();
        Map<CompetenceUnit, List<CompetenceAssessment>> byUnit = all.stream().collect(Collectors.groupingBy(a -> a.getTask().getUnit()));
        return byUnit.entrySet().stream().map(e -> {
            CompetenceUnit u = e.getKey();
            Map<Long, CompetenceAssessment> latest = latestByTask(e.getValue());
            List<MyTask> tasks = u.getTasks().stream().map(t -> {
                CompetenceAssessment a = latest.get(t.getId());
                return new MyTask(t.getName(), t.getKind().name(), a == null ? INCOMPLETE : a.getOutcome().name(), a == null ? 0 : a.getAttempt(),
                        a == null ? null : a.getAssessedOn());
            }).toList();
            return new MyUnit(u.getModule().getCode(), u.getModule().getTitle(), u.getSemester().getName(), unitOutcome(u, latest), tasks);
        }).sorted(Comparator.comparing(MyUnit::code)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private UnitView view(CompetenceUnit u) {
        Long me = CurrentUser.requireId();
        List<ModuleRegistration> regs = classList(u);
        List<CompetenceAssessment> all = assessments.findByUnit(u.getId());
        int max = regulations.settings().getNvaMaxAttempts();
        Map<String, List<CompetenceAssessment>> byKey = all.stream().collect(Collectors.groupingBy(a -> key(a.getTask().getId(), a.getModuleRegistrationId())));
        Map<Long, String> outcomes = outcomes(u, regs, all);
        List<CompetenceDtos.Row> rows = regs.stream().map(m -> {
            Student s = m.getRegistration().getStudent();
            Map<Long, Cell> cells = new LinkedHashMap<>();
            for (CompetenceTask t : u.getTasks()) {
                List<CompetenceAssessment> h = byKey.getOrDefault(key(t.getId(), m.getId()), List.of()).stream()
                        .sorted(Comparator.comparingInt(CompetenceAssessment::getAttempt).reversed()).toList();
                if (h.isEmpty()) continue;
                List<Cell> earlier = h.stream().skip(1).map(a -> cell(a, max, List.of())).toList();
                cells.put(t.getId(), cell(h.get(0), max, earlier));
            }
            return new CompetenceDtos.Row(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), cells, outcomes.get(m.getId()));
        }).toList();
        Map<Long, String> names = names(List.of(u));
        boolean assessor = isAssessor(u, me);
        boolean draft = u.getStatus() == CompetenceUnit.Status.DRAFT;
        Permissions can = new Permissions(canEditTasks(u), canAssign(u), assessor && draft,
                assessor && draft && !regs.isEmpty() && !outcomes.containsValue(INCOMPLETE), canModerate(u) && u.getStatus() == CompetenceUnit.Status.SUBMITTED,
                canModerate(u) && u.getStatus() == CompetenceUnit.Status.SUBMITTED,
                access.has(ExamAccess.EXAMS_OFFICE) && u.getStatus() == CompetenceUnit.Status.VERIFIED && outcomes.containsValue(NYC));
        return new UnitView(u.getId(), u.getSemester().getId(), u.getSemester().getName(),
                u.getSemester().getAcademicYear() == null ? null : u.getSemester().getAcademicYear().getCode(), u.getModule().getCode(),
                u.getModule().getTitle(), dept(u), u.getStatus().name(), max,
                u.getTasks().stream().map(t -> new TaskView(t.getId(), t.getName(), t.getKind().name(), t.getEvidenceRequired(), t.isRequired())).toList(),
                u.getAssessors().stream().map(a -> new Person(a.getUserId(), names.getOrDefault(a.getUserId(), "—")))
                        .sorted(Comparator.comparing(Person::fullName)).toList(),
                rows, count(outcomes, COMPETENT), count(outcomes, NYC), count(outcomes, INCOMPLETE), u.getReturnedNote(), u.getReturnedByName(),
                u.getSubmittedByName(), u.getSubmittedAt(), u.getVerifiedByName(), u.getVerifiedAt(), can);
    }

    private static Cell cell(CompetenceAssessment a, int max, List<Cell> earlier) {
        return new Cell(a.getId(), a.getAttempt(), a.getOutcome().name(), a.getPracticalScore(), a.getTheoryScore(), a.getAssessedOn(), a.getEvidenceRef(),
                a.getComment(), a.getAssessorName(), a.isVerified(), Math.max(0, max - a.getAttempt()), earlier);
    }

    /** Each trainee's unit outcome: competent in every required task's latest attempt, not yet competent in one, or not fully assessed. */
    private Map<Long, String> outcomes(CompetenceUnit u, List<ModuleRegistration> regs, List<CompetenceAssessment> all) {
        Map<Long, List<CompetenceAssessment>> byReg = all.stream().collect(Collectors.groupingBy(CompetenceAssessment::getModuleRegistrationId));
        Map<Long, String> out = new HashMap<>();
        for (ModuleRegistration m : regs) out.put(m.getId(), unitOutcome(u, latestByTask(byReg.getOrDefault(m.getId(), List.of()))));
        return out;
    }

    private static String unitOutcome(CompetenceUnit u, Map<Long, CompetenceAssessment> latest) {
        boolean nyc = false;
        for (CompetenceTask t : u.getTasks()) {
            if (!t.isRequired()) continue;
            CompetenceAssessment a = latest.get(t.getId());
            if (a == null) return INCOMPLETE;
            if (a.getOutcome() == CompetenceAssessment.Outcome.NOT_YET_COMPETENT) nyc = true;
        }
        return nyc ? NYC : COMPETENT;
    }

    private static Map<Long, CompetenceAssessment> latestByTask(List<CompetenceAssessment> list) {
        Map<Long, CompetenceAssessment> m = new HashMap<>();
        for (CompetenceAssessment a : list) m.merge(a.getTask().getId(), a, (x, y) -> x.getAttempt() >= y.getAttempt() ? x : y);
        return m;
    }

    /** For each trainee, the outcome of each module in verified units (the latest verified unit wins). */
    private Map<Long, Map<Long, String>> verifiedOutcomes(Set<Long> studentIds) {
        Map<Long, Map<Long, String>> out = new HashMap<>();
        if (studentIds.isEmpty()) return out;
        List<CompetenceAssessment> all = assessments.findByStudents(studentIds).stream().filter(CompetenceAssessment::isVerified).toList();
        Map<Long, Map<CompetenceUnit, List<CompetenceAssessment>>> grouped = all.stream().collect(Collectors.groupingBy(CompetenceAssessment::getStudentId,
                Collectors.groupingBy(a -> a.getTask().getUnit())));
        grouped.forEach((sid, perUnit) -> perUnit.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getSemester().getStartDate(), Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(e -> out.computeIfAbsent(sid, k -> new HashMap<>()).put(e.getKey().getModule().getId(), unitOutcome(e.getKey(), latestByTask(e.getValue())))));
        return out;
    }

    private boolean wide() {
        return access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC);
    }

    private boolean canEditTasks(CompetenceUnit u) {
        return u.getStatus() == CompetenceUnit.Status.DRAFT && (access.has(ExamAccess.EXAMS_OFFICE) || access.moderates(u.getModule()));
    }

    private boolean canAssign(CompetenceUnit u) {
        return wide() || access.moderates(u.getModule());
    }

    private boolean canModerate(CompetenceUnit u) {
        return access.moderates(u.getModule()) || access.has(Role.INSTITUTION_ADMIN);
    }

    static boolean isAssessor(CompetenceUnit u, Long me) {
        return u.getAssessors().stream().anyMatch(a -> a.getUserId().equals(me));
    }

    private CompetenceUnit require(Long id) {
        return units.findById(id).orElseThrow(() -> new NotFoundException("Competence unit", id));
    }

    private List<ModuleRegistration> classList(CompetenceUnit u) {
        return registrations.students(u.getSemester().getId(), u.getModule().getId(), APPROVED);
    }

    private Map<Long, String> names(List<CompetenceUnit> list) {
        Set<Long> ids = list.stream().flatMap(u -> u.getAssessors().stream().map(CompetenceAssessor::getUserId)).collect(Collectors.toSet());
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static String dept(CompetenceUnit u) {
        return u.getModule().getDepartment() == null ? null : u.getModule().getDepartment().getName();
    }

    private static int count(Map<Long, String> m, String v) {
        return (int) m.values().stream().filter(v::equals).count();
    }

    private static String key(Long taskId, Long regId) {
        return taskId + ":" + regId;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static OffsetDateTime odt(Object o) {
        if (o instanceof java.sql.Timestamp t) return t.toInstant().atZone(ZONE).toOffsetDateTime();
        return (OffsetDateTime) o;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
