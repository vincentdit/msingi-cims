package tz.co.msingi.cims.teaching;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.AssessmentComponent;
import tz.co.msingi.cims.exams.AssessmentScore;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRepositories.AssessmentScoreRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamSettings;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.exams.RegulationService;
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
import tz.co.msingi.cims.teaching.TeachingDtos.EligibilityLine;
import tz.co.msingi.cims.teaching.TeachingDtos.EligibilityOverview;
import tz.co.msingi.cims.teaching.TeachingDtos.EligibilityRules;
import tz.co.msingi.cims.teaching.TeachingDtos.ExemptionRequest;
import tz.co.msingi.cims.teaching.TeachingDtos.ExemptionView;
import tz.co.msingi.cims.teaching.TeachingDtos.GenerateResult;
import tz.co.msingi.cims.teaching.TeachingDtos.OfferingEligibility;
import tz.co.msingi.cims.teaching.TeachingDtos.OfferingEligibilityView;
import tz.co.msingi.cims.teaching.TeachingRepositories.AttendanceRecordRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.EligibilityExemptionRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.ExamEligibilityRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Examination eligibility lists (FR-EXM-04, BRU-08, BP-08 step 2): a student may sit a module's semester examination when their
 * attendance meets the threshold, their continuous assessment is complete and they have examination fees clearance, unless an
 * exemption has been granted. The person who asks for an exemption cannot grant it.
 */
@Service
@RequiredArgsConstructor
public class EligibilityService {

    private static final Set<EligibilityExemption.Status> OPEN = EnumSet.of(EligibilityExemption.Status.PENDING, EligibilityExemption.Status.APPROVED);

    private final ExamEligibilityRepository eligibility;
    private final EligibilityExemptionRepository exemptions;
    private final AttendanceRecordRepository records;
    private final AssessmentScoreRepository scores;
    private final ModuleOfferingRepository offerings;
    private final SemesterRepository semesters;
    private final StudentRepository students;
    private final AttendanceService attendance;
    private final AccountService accounts;
    private final RegulationService regulations;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    public EligibilityRules rules() {
        ExamSettings s = regulations.settings();
        return new EligibilityRules(s.getMinAttendancePercent(), s.isEligibilityRequiresCa(), s.isEligibilityRequiresClearance());
    }

    // ------------------------------------------------------------------ generating the lists

    /**
     * Works out eligibility for every student of every module of the semester whose results have not been submitted yet, and tells
     * students who have just become ineligible. Can be run again as attendance, marks and payments come in.
     */
    @Transactional
    public GenerateResult generate(Long semesterId) {
        access.require("generate examination eligibility lists", ExamAccess.EXAMS_OFFICE);
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        ExamSettings settings = regulations.settings();
        Map<Long, Clearance> clearances = new HashMap<>();
        int done = 0, total = 0, ok = 0, barred = 0, skipped = 0;
        List<String> notes = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (ModuleOffering o : offerings.findBySemesterIdOrderByIdAsc(semesterId)) {
            if (o.getStatus() != ModuleOffering.Status.DRAFT) {
                skipped++;
                notes.add(o.getModule().getCode() + ": results already submitted, list kept as it was");
                continue;
            }
            List<ModuleRegistration> regs = attendance.classList(o);
            Map<Long, Tally> tallies = Tally.byRegistration(records.findByOffering(o.getId()));
            List<AssessmentComponent> ca = o.getComponents().stream().filter(c -> c.getCategory() == AssessmentComponent.Category.CA).toList();
            Map<Long, Set<Long>> caDone = new HashMap<>();
            for (AssessmentScore s : scores.findByOffering(o.getId())) {
                if (s.getComponent().getCategory() == AssessmentComponent.Category.CA && !s.isAbsent() && s.getScore() != null) {
                    caDone.computeIfAbsent(s.getModuleRegistrationId(), k -> new HashSet<>()).add(s.getComponent().getId());
                }
            }
            Set<Long> exempted = exemptions.findByOfferingIdInAndStatus(List.of(o.getId()), EligibilityExemption.Status.APPROVED).stream()
                    .map(EligibilityExemption::getModuleRegistrationId).collect(Collectors.toSet());
            Map<Long, ExamEligibility> existing = eligibility.findByOfferingId(o.getId()).stream()
                    .collect(Collectors.toMap(ExamEligibility::getModuleRegistrationId, Function.identity()));
            Set<Long> inClass = new HashSet<>();
            for (ModuleRegistration m : regs) {
                inClass.add(m.getId());
                Student st = m.getRegistration().getStudent();
                List<String> reasons = new ArrayList<>();
                Tally t = tallies.getOrDefault(m.getId(), Tally.NONE);
                boolean attendanceMet = !t.below(settings.getMinAttendancePercent());
                if (!attendanceMet) {
                    reasons.add("Attendance " + AttendanceService.pct(t.percent()) + " is below " + AttendanceService.pct(settings.getMinAttendancePercent()));
                }
                boolean caMet = true;
                if (settings.isEligibilityRequiresCa()) {
                    Set<Long> have = caDone.getOrDefault(m.getId(), Set.of());
                    List<String> missing = ca.stream().filter(c -> !have.contains(c.getId())).map(AssessmentComponent::getName).toList();
                    caMet = missing.isEmpty();
                    if (!caMet) reasons.add("Continuous assessment incomplete: " + String.join(", ", missing));
                }
                boolean clearanceMet = true;
                if (settings.isEligibilityRequiresClearance()) {
                    Clearance c = clearances.computeIfAbsent(st.getId(), k -> accounts.evaluate(st, ClearanceGate.Gate.EXAMINATION, sem.getAcademicYear()));
                    clearanceMet = c.cleared();
                    if (!clearanceMet) reasons.add(c.reason() == null ? "Examination fees clearance not granted" : c.reason());
                }
                boolean rulesMet = attendanceMet && caMet && clearanceMet;
                boolean ex = exempted.contains(m.getId());
                ExamEligibility e = existing.get(m.getId());
                boolean wasEligible = e == null || e.isEligible();
                if (e == null) {
                    e = new ExamEligibility();
                    e.setModuleRegistrationId(m.getId());
                }
                e.setOfferingId(o.getId());
                e.setStudentId(m.getStudentId());
                e.setSessionsCounted(t.counted());
                e.setSessionsAttended(t.attended());
                e.setAttendancePercent(t.percent());
                e.setAttendanceMet(attendanceMet);
                e.setCaMet(caMet);
                e.setClearanceMet(clearanceMet);
                e.setRulesMet(rulesMet);
                e.setExempted(ex && !rulesMet);
                e.setEligible(rulesMet || ex);
                e.setDetail(reasons);
                e.setGeneratedByName(CurrentUser.name());
                e.setGeneratedAt(now);
                eligibility.save(e);
                total++;
                if (e.isEligible()) {
                    ok++;
                } else {
                    barred++;
                    if (wasEligible) {
                        messaging.event("EXAM_NOT_ELIGIBLE", st.getPhone(), Map.of("module", o.getModule().getCode(), "semester", sem.getName(),
                                "reason", String.join("; ", reasons)));
                    }
                }
            }
            existing.values().stream().filter(e -> !inClass.contains(e.getModuleRegistrationId())).forEach(eligibility::delete);
            done++;
        }
        audit.record("EXAM_ELIGIBILITY_GENERATED", "Semester", sem.getId(), Map.of("offerings", done, "students", total, "eligible", ok,
                "notEligible", barred));
        return new GenerateResult(done, total, ok, barred, skipped, notes);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public EligibilityOverview overview(Long semesterId) {
        List<ModuleOffering> visible = offerings.findBySemesterIdOrderByIdAsc(semesterId).stream().filter(this::canRead).toList();
        List<Long> ids = visible.stream().map(ModuleOffering::getId).toList();
        Map<Long, List<ExamEligibility>> byOffering = ids.isEmpty() ? Map.of()
                : eligibility.findByOfferingIdIn(ids).stream().collect(Collectors.groupingBy(ExamEligibility::getOfferingId));
        Map<Long, Long> pending = ids.isEmpty() ? Map.of() : exemptions.findByOfferingIdInAndStatus(ids, EligibilityExemption.Status.PENDING).stream()
                .collect(Collectors.groupingBy(EligibilityExemption::getOfferingId, Collectors.counting()));
        List<OfferingEligibility> rows = visible.stream().sorted(Comparator.comparing((ModuleOffering o) -> o.getModule().getCode())).map(o -> {
            List<ExamEligibility> es = byOffering.getOrDefault(o.getId(), List.of());
            ExamEligibility latest = es.stream().max(Comparator.comparing(ExamEligibility::getGeneratedAt)).orElse(null);
            return new OfferingEligibility(o.getId(), o.getModule().getCode(), o.getModule().getTitle(),
                    o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(), o.getStatus().name(),
                    es.isEmpty() ? attendance.classList(o).size() : es.size(), (int) es.stream().filter(ExamEligibility::isEligible).count(),
                    (int) es.stream().filter(e -> !e.isEligible()).count(), (int) es.stream().filter(ExamEligibility::isExempted).count(),
                    pending.getOrDefault(o.getId(), 0L).intValue(), latest == null ? null : latest.getGeneratedAt(),
                    latest == null ? null : latest.getGeneratedByName());
        }).toList();
        return new EligibilityOverview(semesterId, rules(), access.has(ExamAccess.EXAMS_OFFICE), access.has(ExamAccess.COMMITTEE), rows);
    }

    @Transactional(readOnly = true)
    public OfferingEligibilityView offering(Long offeringId) {
        ModuleOffering o = offerings.findById(offeringId).orElseThrow(() -> new NotFoundException("Module offering", offeringId));
        if (!canRead(o) && !attendance.isMine(o, access.me())) throw new AccessDeniedException("Not your module");
        Map<Long, ExamEligibility> elig = eligibility.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(ExamEligibility::getModuleRegistrationId, Function.identity()));
        Map<Long, EligibilityExemption> latestExemption = new HashMap<>();
        for (EligibilityExemption x : exemptions.findByOfferingIdOrderByRequestedAtDesc(o.getId())) latestExemption.putIfAbsent(x.getModuleRegistrationId(), x);
        List<ModuleRegistration> regs = attendance.classList(o);
        Map<Long, Student> studentsById = regs.stream().map(m -> m.getRegistration().getStudent()).collect(Collectors.toMap(Student::getId, Function.identity(), (a, b) -> a));
        List<EligibilityLine> lines = regs.stream().map(m -> {
            Student s = m.getRegistration().getStudent();
            ExamEligibility e = elig.get(m.getId());
            EligibilityExemption x = latestExemption.get(m.getId());
            return new EligibilityLine(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(),
                    e == null ? null : e.getAttendancePercent(), e == null ? 0 : e.getSessionsCounted(), e == null ? 0 : e.getSessionsAttended(),
                    e != null && e.isAttendanceMet(), e != null && e.isCaMet(), e != null && e.isClearanceMet(), e != null && e.isRulesMet(),
                    e != null && e.isExempted(), e != null && e.isEligible(), e == null ? List.of() : e.getDetail(),
                    x == null ? null : view(x, o, studentsById, e));
        }).toList();
        ExamEligibility latest = elig.values().stream().max(Comparator.comparing(ExamEligibility::getGeneratedAt)).orElse(null);
        Semester sem = o.getSemester();
        boolean draft = o.getStatus() == ModuleOffering.Status.DRAFT;
        return new OfferingEligibilityView(o.getId(), sem.getId(), sem.getName(), o.getModule().getCode(), o.getModule().getTitle(),
                o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(), o.getStatus().name(), rules(),
                latest == null ? null : latest.getGeneratedAt(), latest == null ? null : latest.getGeneratedByName(),
                draft && latest != null && canRequest(o), draft && access.has(ExamAccess.COMMITTEE), lines);
    }

    /** Exemptions across the institution, pending first (for the people who decide them). */
    @Transactional(readOnly = true)
    public List<ExemptionView> exemptions(boolean pendingOnly) {
        if (!access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.HEAD_OF_DEPARTMENT)) {
            throw new AccessDeniedException("Not allowed to see examination exemptions");
        }
        List<EligibilityExemption> list = pendingOnly ? exemptions.findByStatusOrderByRequestedAtAsc(EligibilityExemption.Status.PENDING)
                : exemptions.findTop100ByOrderByRequestedAtDesc();
        Map<Long, ModuleOffering> os = offerings.findAllById(list.stream().map(EligibilityExemption::getOfferingId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ModuleOffering::getId, Function.identity()));
        Map<Long, Student> ss = students.findAllById(list.stream().map(EligibilityExemption::getStudentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        return list.stream().filter(x -> canRead(os.get(x.getOfferingId())))
                .map(x -> view(x, os.get(x.getOfferingId()), ss, eligibility.findByModuleRegistrationId(x.getModuleRegistrationId()).orElse(null)))
                .toList();
    }

    // ------------------------------------------------------------------ exemptions

    @Transactional
    public ExemptionView request(Long offeringId, ExemptionRequest req) {
        ModuleOffering o = offerings.findById(offeringId).orElseThrow(() -> new NotFoundException("Module offering", offeringId));
        if (!canRequest(o)) throw new AccessDeniedException("Only the Examinations Office, academic management or the module's Head of Department ask for exemptions");
        requireDraft(o);
        ModuleRegistration m = attendance.classList(o).stream().filter(r -> r.getId().equals(req.moduleRegistrationId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("NOT_IN_CLASS", "That student is not registered for this module"));
        ExamEligibility e = eligibility.findByModuleRegistrationId(m.getId()).orElseThrow(() ->
                new BusinessRuleException("NO_ELIGIBILITY_LIST", "Generate the eligibility list for this module first"));
        if (e.isRulesMet()) throw new BusinessRuleException("ALREADY_ELIGIBLE", "This student already meets the eligibility rules");
        if (exemptions.findFirstByModuleRegistrationIdAndStatusIn(m.getId(), OPEN).isPresent()) {
            throw new BusinessRuleException("EXEMPTION_EXISTS", "An exemption for this student in this module is already pending or granted");
        }
        EligibilityExemption x = new EligibilityExemption();
        x.setOfferingId(o.getId());
        x.setModuleRegistrationId(m.getId());
        x.setStudentId(m.getStudentId());
        x.setReason(req.reason().trim());
        x.setRequestedById(access.me());
        x.setRequestedByName(CurrentUser.name());
        exemptions.save(x);
        Student s = m.getRegistration().getStudent();
        audit.record("EXAM_EXEMPTION_REQUESTED", "EligibilityExemption", x.getId(), Map.of("module", o.getModule().getCode(),
                "student", s.getRegistrationNo(), "reason", x.getReason()));
        return view(x, o, Map.of(s.getId(), s), e);
    }

    @Transactional
    public ExemptionView approve(Long id, String note) {
        return decide(id, true, note);
    }

    @Transactional
    public ExemptionView reject(Long id, String note) {
        if (note == null || note.isBlank()) throw new BusinessRuleException("NOTE_REQUIRED", "Say why the exemption is rejected");
        return decide(id, false, note);
    }

    private ExemptionView decide(Long id, boolean approve, String note) {
        access.require("decide examination exemptions", ExamAccess.COMMITTEE);
        EligibilityExemption x = exemptions.findById(id).orElseThrow(() -> new NotFoundException("Exemption", id));
        if (x.getStatus() != EligibilityExemption.Status.PENDING) throw new BusinessRuleException("ALREADY_DECIDED", "This exemption has already been decided");
        if (Objects.equals(x.getRequestedById(), access.me())) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "You asked for this exemption, so someone else must decide it");
        }
        ModuleOffering o = offerings.findById(x.getOfferingId()).orElseThrow();
        requireDraft(o);
        x.setStatus(approve ? EligibilityExemption.Status.APPROVED : EligibilityExemption.Status.REJECTED);
        x.setDecidedByName(CurrentUser.name());
        x.setDecidedAt(OffsetDateTime.now());
        x.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        ExamEligibility e = eligibility.findByModuleRegistrationId(x.getModuleRegistrationId()).orElse(null);
        Student s = students.findById(x.getStudentId()).orElseThrow();
        if (approve && e != null) {
            e.setExempted(!e.isRulesMet());
            e.setEligible(true);
            messaging.event("EXAM_EXEMPTION_APPROVED", s.getPhone(), Map.of("module", o.getModule().getCode(), "semester", o.getSemester().getName()));
        }
        audit.record(approve ? "EXAM_EXEMPTION_APPROVED" : "EXAM_EXEMPTION_REJECTED", "EligibilityExemption", x.getId(),
                Map.of("module", o.getModule().getCode(), "student", s.getRegistrationNo(), "note", String.valueOf(x.getDecisionNote())));
        return view(x, o, Map.of(s.getId(), s), e);
    }

    // ------------------------------------------------------------------ access and views

    private boolean canRead(ModuleOffering o) {
        return o != null && access.follows(o.getModule());
    }

    private boolean canRequest(ModuleOffering o) {
        return access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC) || access.moderates(o.getModule());
    }

    private static void requireDraft(ModuleOffering o) {
        if (o.getStatus() != ModuleOffering.Status.DRAFT) {
            throw new BusinessRuleException("RESULTS_SUBMITTED", "The results of " + o.getModule().getCode() + " have been submitted, so eligibility can no longer change");
        }
    }

    private ExemptionView view(EligibilityExemption x, ModuleOffering o, Map<Long, Student> ss, ExamEligibility e) {
        Student s = ss.get(x.getStudentId());
        if (s == null) s = students.findById(x.getStudentId()).orElse(null);
        boolean canDecide = x.getStatus() == EligibilityExemption.Status.PENDING && access.has(ExamAccess.COMMITTEE)
                && !Objects.equals(x.getRequestedById(), access.me()) && o.getStatus() == ModuleOffering.Status.DRAFT;
        return new ExemptionView(x.getId(), o.getId(), o.getModule().getCode(), x.getModuleRegistrationId(), x.getStudentId(),
                s == null ? null : s.getRegistrationNo(), s == null ? null : s.fullName(), x.getReason(), x.getStatus().name(), x.getRequestedByName(),
                x.getRequestedAt(), x.getDecidedByName(), x.getDecidedAt(), x.getDecisionNote(), e == null ? List.of() : e.getDetail(), canDecide);
    }
}
