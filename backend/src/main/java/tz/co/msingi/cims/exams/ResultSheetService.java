package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
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
import tz.co.msingi.cims.exams.ExamDtos.ComputeRequest;
import tz.co.msingi.cims.exams.ExamDtos.DecisionRequest;
import tz.co.msingi.cims.exams.ExamDtos.SheetCell;
import tz.co.msingi.cims.exams.ExamDtos.SheetModule;
import tz.co.msingi.cims.exams.ExamDtos.SheetPermissions;
import tz.co.msingi.cims.exams.ExamDtos.SheetRow;
import tz.co.msingi.cims.exams.ExamDtos.SheetSummary;
import tz.co.msingi.cims.exams.ExamDtos.SheetView;
import tz.co.msingi.cims.exams.ExamDtos.StudentModuleResult;
import tz.co.msingi.cims.exams.ExamDtos.StudentResults;
import tz.co.msingi.cims.exams.ExamDtos.StudentSemesterResult;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResultSheetRepository;
import tz.co.msingi.cims.exams.ExamRepositories.SemesterResultRepository;
import tz.co.msingi.cims.exams.ModuleResult.Remark;
import tz.co.msingi.cims.exams.SemesterResult.Decision;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Class result sheets (FR-EXM-06/07, BP-08 steps 6-9): the Examinations Officer computes GPA, CGPA and proposed decisions once every
 * module is approved by its department; the Academic Committee reviews (and may change a decision with a reason) and recommends;
 * the Governing Board confirms, which locks the results; the Examinations Officer publishes them to students (FR-EXM-10).
 */
@Service
@RequiredArgsConstructor
public class ResultSheetService {

    private static final SemesterRegistration.Status APPROVED = SemesterRegistration.Status.APPROVED;
    private static final Set<ResultSheet.Status> FINAL = EnumSet.of(ResultSheet.Status.CONFIRMED, ResultSheet.Status.PUBLISHED);

    private final ResultSheetRepository sheets;
    private final SemesterResultRepository semesterResults;
    private final ModuleResultRepository moduleResults;
    private final ModuleOfferingRepository offerings;
    private final ExamRegistrationQueries registrations;
    private final SemesterRepository semesters;
    private final ProgrammeRepository programmes;
    private final StudentRepository students;
    private final RegulationService regulations;
    private final AccountService accounts;
    private final Messaging messaging;
    private final ExamAccess access;
    private final AuditService audit;

    // ------------------------------------------------------------------ overview

    /** Every class with approved registrations in the semester, with its sheet (if computed) and the modules still awaiting approval. */
    @Transactional(readOnly = true)
    public List<SheetSummary> list(Long semesterId) {
        Map<String, ResultSheet> existing = sheets.findBySemesterIdOrderByProgrammeIdAscYearOfStudyAsc(semesterId).stream()
                .collect(Collectors.toMap(s -> s.getProgramme().getId() + "/" + s.getYearOfStudy(), Function.identity()));
        Map<Long, ModuleOffering> byModule = offerings.findBySemesterIdOrderByIdAsc(semesterId).stream()
                .collect(Collectors.toMap(o -> o.getModule().getId(), Function.identity()));
        Map<Long, Programme> progs = programmes.findAll().stream().collect(Collectors.toMap(Programme::getId, Function.identity()));
        List<SheetSummary> out = new ArrayList<>();
        for (Object[] c : registrations.classes(semesterId, APPROVED)) {
            Long programmeId = (Long) c[0];
            short year = ((Number) c[1]).shortValue();
            int count = ((Number) c[2]).intValue();
            Programme p = progs.get(programmeId);
            List<String> pending = pending(registrations.classOf(semesterId, programmeId, year, APPROVED), byModule);
            ResultSheet s = existing.get(programmeId + "/" + year);
            out.add(new SheetSummary(s == null ? null : s.getId(), semesterId, programmeId, p.getCode(), p.getName(), year, count,
                    s == null ? "NOT_COMPUTED" : s.getStatus().name(), pending, s == null ? null : s.getComputedAt(),
                    s == null ? null : s.getPublishedAt(), s == null ? Map.of() : decisions(s)));
        }
        out.sort(Comparator.comparing(SheetSummary::programmeCode).thenComparing(SheetSummary::yearOfStudy));
        return out;
    }

    @Transactional(readOnly = true)
    public SheetView get(Long id) {
        return view(require(id));
    }

    // ------------------------------------------------------------------ BP-08 step 6: computation

    @Transactional
    public SheetView compute(ComputeRequest req) {
        access.require("compute results", ExamAccess.EXAMS_OFFICE);
        Semester sem = semesters.findById(req.semesterId()).orElseThrow(() -> new NotFoundException("Semester", req.semesterId()));
        Programme p = programmes.findById(req.programmeId()).orElseThrow(() -> new NotFoundException("Programme", req.programmeId()));
        if (p.getAwardFamily() != Programme.AwardFamily.NTA) {
            throw new BusinessRuleException("NOT_GRADED", "NVA programmes are assessed by competence; their outcomes are recorded separately.");
        }
        ResultSheet sheet = sheets.findBySemesterIdAndProgrammeIdAndYearOfStudy(sem.getId(), p.getId(), req.yearOfStudy()).orElse(null);
        if (sheet != null && sheet.getStatus() != ResultSheet.Status.COMPUTED) {
            throw new BusinessRuleException("SHEET_ALREADY_RECOMMENDED", "This result sheet is " + sheet.getStatus().name().toLowerCase()
                    + ". It can be recomputed only if the Academic Committee or Board returns it.");
        }
        List<SemesterRegistration> regs = registrations.classOf(sem.getId(), p.getId(), req.yearOfStudy(), APPROVED);
        if (regs.isEmpty()) throw new BusinessRuleException("NO_STUDENTS", "No approved semester registrations for this class");
        Map<Long, ModuleOffering> byModule = offerings.findBySemesterIdOrderByIdAsc(sem.getId()).stream()
                .collect(Collectors.toMap(o -> o.getModule().getId(), Function.identity()));
        List<String> pending = pending(regs, byModule);
        if (!pending.isEmpty()) {
            throw new BusinessRuleException("MODULES_NOT_APPROVED", "Waiting for department approval of " + String.join(", ", pending));
        }
        RegulationSet rs = regulations.forLevel(p.getAwardFamily(), p.getAwardLevel(), sem.getAcademicYear());
        if (sheet == null) {
            sheet = new ResultSheet();
            sheet.setSemester(sem);
            sheet.setProgramme(p);
            sheet.setYearOfStudy(req.yearOfStudy());
        }
        sheet.setRegulationSet(rs);
        sheet.setComputedByName(CurrentUser.name());
        sheet.setComputedAt(OffsetDateTime.now());
        sheet.setReturnedNote(null);

        List<Long> regIds = regs.stream().flatMap(r -> r.getModules().stream()).map(ModuleRegistration::getId).toList();
        Map<Long, ModuleResult> results = moduleResults.findByModuleRegistrationIdIn(regIds).stream()
                .collect(Collectors.toMap(ModuleResult::getModuleRegistrationId, Function.identity()));
        Map<Long, SemesterResult> previous = sheet.getResults().stream().collect(Collectors.toMap(SemesterResult::getStudentId, Function.identity()));
        Set<Long> present = regs.stream().map(r -> r.getStudent().getId()).collect(Collectors.toSet());
        sheet.getResults().removeIf(r -> !present.contains(r.getStudentId()));
        for (SemesterRegistration r : regs) {
            List<Grading.ModuleOutcome> outcomes = new ArrayList<>();
            BigDecimal registered = BigDecimal.ZERO, passed = BigDecimal.ZERO, graded = BigDecimal.ZERO, points = BigDecimal.ZERO;
            for (ModuleRegistration m : r.getModules()) {
                ModuleResult mr = results.get(m.getId());
                Grading.ModuleOutcome o = mr == null
                        ? new Grading.ModuleOutcome(null, null, null, null, null, Remark.INCOMPLETE, "No result")
                        : new Grading.ModuleOutcome(mr.getCaTotal(), mr.getSeTotal(), mr.getTotal(), mr.getGrade(), mr.getGradePoints(), mr.getRemark(),
                        mr.getFailReason());
                outcomes.add(o);
                registered = registered.add(m.getCredits());
                if (o.remark() == Remark.PASS) passed = passed.add(m.getCredits());
                if (o.graded()) {
                    graded = graded.add(m.getCredits());
                    points = points.add(o.points().multiply(m.getCredits()));
                }
            }
            BigDecimal gpa = Grading.gpa(rs, points, graded);
            Decision proposed = Grading.decide(rs, outcomes, gpa);
            SemesterResult sr = previous.get(r.getStudent().getId());
            if (sr == null) {
                sr = new SemesterResult();
                sr.setSheet(sheet);
                sr.setStudentId(r.getStudent().getId());
                sheet.getResults().add(sr);
            }
            boolean overridden = sr.getDecisionReason() != null && sr.getDecision() != null && sr.getDecision() != sr.getProposedDecision();
            sr.setCreditsRegistered(registered);
            sr.setCreditsPassed(passed);
            sr.setCreditsGraded(graded);
            sr.setPointsTotal(points);
            sr.setGpa(gpa);
            sr.setModulesFailed((short) outcomes.stream().filter(o -> o.remark() == Remark.FAIL).count());
            sr.setModulesIncomplete((short) outcomes.stream().filter(o -> !o.graded()).count());
            sr.setProposedDecision(proposed);
            if (!overridden) {
                sr.setDecision(proposed);
                sr.setDecisionReason(null);
            }
            // CGPA over this semester and every confirmed earlier semester of the student
            BigDecimal allPoints = points, allCredits = graded;
            for (SemesterResult earlier : semesterResults.findByStudentIdOrderByIdAsc(r.getStudent().getId())) {
                if (earlier.getSheet() == sheet || Objects.equals(earlier.getSheet().getId(), sheet.getId())) continue;
                if (!FINAL.contains(earlier.getSheet().getStatus())) continue;
                if (!earlier.getSheet().getSemester().getStartDate().isBefore(sem.getStartDate())) continue;
                allPoints = allPoints.add(earlier.getPointsTotal());
                allCredits = allCredits.add(earlier.getCreditsGraded());
            }
            sr.setCgpa(Grading.gpa(rs, allPoints, allCredits));
            sr.setClassification(Grading.classify(rs, sr.getCgpa()));
        }
        sheets.save(sheet);
        audit.record("RESULTS_COMPUTED", "ResultSheet", sheet.getId(), Map.of("programme", p.getCode(), "year", req.yearOfStudy(),
                "semester", sem.getName(), "students", regs.size()));
        return view(sheet);
    }

    // ------------------------------------------------------------------ BP-08 step 7: Academic Committee

    @Transactional
    public SheetView decide(Long id, Long studentId, DecisionRequest req) {
        access.require("change a progression decision", ExamAccess.COMMITTEE);
        ResultSheet s = require(id);
        if (s.getStatus() != ResultSheet.Status.COMPUTED) throw new BusinessRuleException("WRONG_STAGE", "Decisions change while the Academic Committee reviews the sheet");
        SemesterResult r = s.getResults().stream().filter(x -> x.getStudentId().equals(studentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Student result", studentId));
        boolean differs = req.decision() != r.getProposedDecision();
        if (differs && (req.reason() == null || req.reason().isBlank())) {
            throw new BusinessRuleException("REASON_REQUIRED", "Give the committee's reason for changing the proposed decision");
        }
        Decision before = r.getDecision();
        r.setDecision(req.decision());
        r.setDecisionReason(differs ? req.reason().trim() : null);
        audit.record("RESULT_DECISION_CHANGED", "ResultSheet", s.getId(), Map.of("student", studentId, "from", before.name(),
                "to", req.decision().name(), "reason", differs ? req.reason().trim() : ""));
        return view(s);
    }

    @Transactional
    public SheetView recommend(Long id, String note) {
        access.require("recommend results", ExamAccess.COMMITTEE);
        ResultSheet s = require(id);
        if (s.getStatus() != ResultSheet.Status.COMPUTED) throw new BusinessRuleException("WRONG_STAGE", "Only a computed sheet can be recommended");
        s.setStatus(ResultSheet.Status.RECOMMENDED);
        s.setRecommendedBy(access.me());
        s.setRecommendedByName(CurrentUser.name());
        s.setRecommendedAt(OffsetDateTime.now());
        s.setCommitteeNote(note == null || note.isBlank() ? null : note.trim());
        audit.record("RESULTS_RECOMMENDED", "ResultSheet", s.getId(), Map.of("programme", s.getProgramme().getCode(), "year", s.getYearOfStudy()));
        return view(s);
    }

    // ------------------------------------------------------------------ BP-08 step 8: Governing Board

    @Transactional
    public SheetView confirm(Long id) {
        access.require("confirm results for the Governing Board", ExamAccess.BOARD);
        ResultSheet s = require(id);
        if (s.getStatus() != ResultSheet.Status.RECOMMENDED) {
            throw new BusinessRuleException("WRONG_STAGE", "The Academic Committee recommends the results before the Board confirms them");
        }
        if (Objects.equals(s.getRecommendedBy(), access.me())) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "The person who recommended the results for the Academic Committee cannot also confirm them for the Board");
        }
        s.setStatus(ResultSheet.Status.CONFIRMED);
        s.setConfirmedByName(CurrentUser.name());
        s.setConfirmedAt(OffsetDateTime.now());
        List<Long> regIds = registrations.classOf(s.getSemester().getId(), s.getProgramme().getId(), s.getYearOfStudy(), APPROVED).stream()
                .flatMap(r -> r.getModules().stream()).map(ModuleRegistration::getId).toList();
        for (ModuleResult mr : moduleResults.findByModuleRegistrationIdIn(regIds)) mr.setLocked(true);
        s.getResults().forEach(r -> r.setLocked(true));
        audit.record("RESULTS_CONFIRMED", "ResultSheet", s.getId(), Map.of("programme", s.getProgramme().getCode(), "year", s.getYearOfStudy(),
                "students", s.getResults().size()));
        return view(s);
    }

    @Transactional
    public SheetView returnSheet(Long id, String note) {
        ResultSheet s = require(id);
        if (s.getStatus() != ResultSheet.Status.RECOMMENDED) {
            throw new BusinessRuleException("WRONG_STAGE", s.getStatus() == ResultSheet.Status.COMPUTED ? "The sheet is already with the Examinations Officer"
                    : "Confirmed results are locked and change only by amendment");
        }
        if (!access.has(ExamAccess.BOARD) && !access.has(ExamAccess.COMMITTEE)) throw new AccessDeniedException("Not allowed to return results");
        s.setStatus(ResultSheet.Status.COMPUTED);
        s.setReturnedNote(note.trim());
        s.setReturnedByName(CurrentUser.name());
        s.setReturnedAt(OffsetDateTime.now());
        s.setRecommendedBy(null);
        audit.record("RESULTS_RETURNED_TO_COMMITTEE", "ResultSheet", s.getId(), Map.of("note", note.trim()));
        return view(s);
    }

    // ------------------------------------------------------------------ BP-08 step 9: publication (FR-EXM-10)

    @Transactional
    public SheetView publish(Long id) {
        access.require("publish results", ExamAccess.EXAMS_OFFICE);
        ResultSheet s = require(id);
        if (s.getStatus() != ResultSheet.Status.CONFIRMED) throw new BusinessRuleException("WRONG_STAGE", "Only results confirmed by the Board can be published");
        s.setStatus(ResultSheet.Status.PUBLISHED);
        s.setPublishedByName(CurrentUser.name());
        s.setPublishedAt(OffsetDateTime.now());
        String label = s.getSemester().getName() + " " + s.getSemester().getAcademicYear().getCode();
        int withheld = 0;
        Map<Long, Student> people = students.findAllById(s.getResults().stream().map(SemesterResult::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        for (SemesterResult r : s.getResults()) {
            Student st = people.get(r.getStudentId());
            Clearance c = accounts.evaluate(st, ClearanceGate.Gate.RESULTS, s.getSemester().getAcademicYear());
            if (c.enabled() && !c.cleared()) {
                withheld++;
                messaging.event("RESULTS_WITHHELD", st.getPhone(), Map.of("semester", label, "reason", c.reason()));
            } else {
                messaging.event("RESULTS_PUBLISHED", st.getPhone(), Map.of("semester", label, "gpa", r.getGpa() == null ? "—" : r.getGpa().toPlainString(),
                        "decision", decisionText(r.getDecision())));
            }
        }
        audit.record("RESULTS_PUBLISHED", "ResultSheet", s.getId(), Map.of("programme", s.getProgramme().getCode(), "year", s.getYearOfStudy(),
                "students", s.getResults().size(), "withheld", withheld));
        return view(s);
    }

    // ------------------------------------------------------------------ student portal

    @Transactional(readOnly = true)
    public StudentResults mine() {
        Student st = students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        List<ModuleResult> all = moduleResults.findByStudentId(st.getId());
        Map<Long, ModuleOffering> offeringById = offerings.findAllById(all.stream().map(ModuleResult::getOfferingId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ModuleOffering::getId, Function.identity()));
        List<StudentSemesterResult> out = new ArrayList<>();
        for (SemesterResult r : semesterResults.findByStudentIdOrderByIdAsc(st.getId())) {
            ResultSheet s = r.getSheet();
            if (s.getStatus() != ResultSheet.Status.PUBLISHED) continue;
            Clearance c = accounts.evaluate(st, ClearanceGate.Gate.RESULTS, s.getSemester().getAcademicYear());
            boolean withheld = c.enabled() && !c.cleared();
            List<StudentModuleResult> modules = withheld ? List.of() : all.stream()
                    .filter(m -> offeringById.containsKey(m.getOfferingId()) && offeringById.get(m.getOfferingId()).getSemester().getId().equals(s.getSemester().getId()))
                    .map(m -> {
                        Module mod = offeringById.get(m.getOfferingId()).getModule();
                        return new StudentModuleResult(mod.getCode(), mod.getTitle(), m.getCredits(), m.getGrade(), m.getGradePoints(), m.getRemark().name());
                    }).sorted(Comparator.comparing(StudentModuleResult::code)).toList();
            out.add(new StudentSemesterResult(r.getId(), s.getSemester().getName(), s.getSemester().getAcademicYear().getCode(), s.getYearOfStudy(),
                    s.getPublishedAt(), withheld, withheld ? c.reason() : null, modules, r.getCreditsRegistered(),
                    withheld ? null : r.getCreditsPassed(), withheld ? null : r.getGpa(), withheld ? null : r.getCgpa(),
                    withheld ? null : r.getDecision().name()));
        }
        out.sort(Comparator.comparing(StudentSemesterResult::publishedAt).reversed());
        return new StudentResults(out);
    }

    // ------------------------------------------------------------------ view

    SheetView view(ResultSheet s) {
        List<SemesterRegistration> regs = registrations.classOf(s.getSemester().getId(), s.getProgramme().getId(), s.getYearOfStudy(), APPROVED);
        Map<Long, SemesterRegistration> regByStudent = regs.stream().collect(Collectors.toMap(r -> r.getStudent().getId(), Function.identity()));
        Map<Long, ModuleResult> results = moduleResults.findByModuleRegistrationIdIn(
                        regs.stream().flatMap(r -> r.getModules().stream()).map(ModuleRegistration::getId).toList()).stream()
                .collect(Collectors.toMap(ModuleResult::getModuleRegistrationId, Function.identity()));
        TreeMap<String, SheetModule> modules = new TreeMap<>();
        regs.forEach(r -> r.getModules().forEach(m -> modules.putIfAbsent(m.getModule().getCode(),
                new SheetModule(m.getModule().getId(), m.getModule().getCode(), m.getModule().getTitle(), m.getCredits()))));
        List<SheetRow> rows = new ArrayList<>();
        for (SemesterResult r : s.getResults()) {
            SemesterRegistration reg = regByStudent.get(r.getStudentId());
            Student st = reg != null ? reg.getStudent() : students.findById(r.getStudentId()).orElseThrow();
            Map<String, SheetCell> cells = new LinkedHashMap<>();
            if (reg != null) {
                for (ModuleRegistration m : reg.getModules()) {
                    ModuleResult mr = results.get(m.getId());
                    cells.put(m.getModule().getCode(), mr == null ? new SheetCell(null, null, Remark.INCOMPLETE.name())
                            : new SheetCell(mr.getGrade(), mr.getTotal(), mr.getRemark().name()));
                }
            }
            rows.add(new SheetRow(st.getId(), st.getRegistrationNo(), st.fullName(), st.getSex(), cells, r.getCreditsRegistered(), r.getCreditsPassed(),
                    r.getGpa(), r.getCgpa(), r.getModulesFailed(), r.getProposedDecision().name(), r.getDecision().name(), r.getDecisionReason(),
                    r.getClassification()));
        }
        rows.sort(Comparator.comparing(SheetRow::registrationNo));
        boolean computed = s.getStatus() == ResultSheet.Status.COMPUTED;
        SheetPermissions can = new SheetPermissions(computed && access.has(ExamAccess.EXAMS_OFFICE), computed && access.has(ExamAccess.COMMITTEE),
                computed && access.has(ExamAccess.COMMITTEE),
                s.getStatus() == ResultSheet.Status.RECOMMENDED && access.has(ExamAccess.BOARD) && !Objects.equals(s.getRecommendedBy(), access.me()),
                s.getStatus() == ResultSheet.Status.CONFIRMED && access.has(ExamAccess.EXAMS_OFFICE),
                s.getStatus() == ResultSheet.Status.RECOMMENDED && (access.has(ExamAccess.BOARD) || access.has(ExamAccess.COMMITTEE)));
        Semester sem = s.getSemester();
        return new SheetView(s.getId(), sem.getId(), sem.getName(), sem.getAcademicYear().getCode(), s.getProgramme().getId(), s.getProgramme().getCode(),
                s.getProgramme().getName(), s.getYearOfStudy(), s.getStatus().name(), s.getRegulationSet().getName(), new ArrayList<>(modules.values()), rows,
                decisions(s), s.getComputedByName(), s.getComputedAt(), s.getRecommendedByName(), s.getRecommendedAt(), s.getCommitteeNote(),
                s.getConfirmedByName(), s.getConfirmedAt(), s.getPublishedByName(), s.getPublishedAt(), s.getReturnedNote(), s.getReturnedByName(),
                s.getReturnedAt(), can);
    }

    /** Module codes of the class whose results have not passed every department stage. */
    private static List<String> pending(List<SemesterRegistration> regs, Map<Long, ModuleOffering> byModule) {
        return regs.stream().flatMap(r -> r.getModules().stream()).map(ModuleRegistration::getModule).distinct()
                .filter(m -> byModule.get(m.getId()) == null || !OfferingService.departmentApproved(byModule.get(m.getId())))
                .map(Module::getCode).sorted().distinct().toList();
    }

    private static Map<String, Long> decisions(ResultSheet s) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Decision d : Decision.values()) {
            long n = s.getResults().stream().filter(r -> r.getDecision() == d).count();
            if (n > 0) m.put(d.name(), n);
        }
        return m;
    }

    static String decisionText(Decision d) {
        return switch (d) {
            case PASS -> "Pass";
            case SUPPLEMENTARY -> "Supplementary examination";
            case SPECIAL_EXAM -> "Special examination";
            case INCOMPLETE -> "Incomplete";
            case REPEAT -> "Repeat";
            case DISCONTINUE -> "Discontinued";
        };
    }

    private ResultSheet require(Long id) {
        return sheets.findById(id).orElseThrow(() -> new NotFoundException("Result sheet", id));
    }
}
