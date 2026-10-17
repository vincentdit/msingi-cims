package tz.co.msingi.cims.registry.semester;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.Curriculum;
import tz.co.msingi.cims.academic.curriculum.CurriculumModule;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumModuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.ElectiveRuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumService;
import tz.co.msingi.cims.academic.curriculum.ElectiveRule;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.ApproveAllResult;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.LateFee;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.ModuleOption;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.RegisteredModule;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.RegistrationView;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.SemesterInfo;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.SemesterSummary;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationDtos.StudentRegistration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Semester registration (FR-REG-03, BP-05): the student confirms their details and modules (core from the curriculum,
 * electives within its rules) while registration is open, fee clearance is checked, and Registry approves.
 */
@Service
@RequiredArgsConstructor
public class SemesterRegistrationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final SemesterRegistrationRepository registrations;
    private final tz.co.msingi.cims.platform.security.DataScope scope;
    private final SemesterRepository semesters;
    private final CurriculumService curricula;
    private final CurriculumModuleRepository curriculumModules;
    private final ElectiveRuleRepository electiveRules;
    private final StudentRepository students;
    private final AccountService accounts;
    private final Messaging messaging;
    private final AuditService audit;
    private final tz.co.msingi.cims.finance.service.BillingService billing;
    private final tz.co.msingi.cims.health.HealthCoverService health;

    /** Invoice purpose of the late-registration fee (BP-05). */
    public static final String LATE_FEE_PURPOSE = "LATE_REGISTRATION";

    // ------------------------------------------------------------------ student

    /** The signed-in student's registration page. */
    @Transactional(readOnly = true)
    public StudentRegistration mine() {
        Student s = me();
        StudentRegistration r = mine(s);
        boolean open = "OPEN".equals(r.state()) || "LATE".equals(r.state());
        if (!open || r.semester() == null) return r;
        Long yearId = semesters.findById(r.semester().id()).orElseThrow().getAcademicYear().getId();
        return health.coveredForRegistration(s.getId(), yearId) ? r : r.withHealthCoverMissing(true);
    }

    private StudentRegistration mine(Student s) {
        LocalDate today = LocalDate.now(ZONE);
        Semester sem = currentSemester(today);
        List<RegistrationView> history = registrations.findByStudentIdOrderBySubmittedAtDesc(s.getId()).stream().map(this::view).toList();
        if (sem == null) {
            return new StudentRegistration(null, "NO_SEMESTER", "No semester has been set up for registration yet.", s.getYearOfStudy(), null,
                    List.of(), List.of(), 0, 0, null, null, history);
        }
        SemesterInfo info = info(sem, today);
        SemesterRegistration existing = registrations.findByStudentIdAndSemesterId(s.getId(), sem.getId()).orElse(null);
        RegistrationView reg = existing == null ? null : view(existing);
        Clearance clearance = accounts.evaluate(s, ClearanceGate.Gate.REGISTRATION, sem.getAcademicYear());
        if (s.getStatus() != Student.Status.ACTIVE) {
            return new StudentRegistration(info, "NOT_ACTIVE", s.getStatus() == Student.Status.ADMITTED
                    ? "Complete your first registration at Registry (report with your original documents) before registering for a semester."
                    : "Your status (" + s.getStatus().name().toLowerCase() + ") does not allow semester registration. Contact Registry.",
                    s.getYearOfStudy(), null, List.of(), List.of(), 0, 0, clearance, reg, history);
        }
        Curriculum c = curricula.curriculumFor(s).orElse(null);
        if (c == null) {
            return new StudentRegistration(info, "NO_CURRICULUM", "Your programme's curriculum has not been approved yet. Registry will tell you when you can register.",
                    s.getYearOfStudy(), null, List.of(), List.of(), 0, 0, clearance, reg, history);
        }
        List<CurriculumModule> slot = curriculumModules.findByCurriculumIdAndYearOfStudyAndSemesterNo(c.getId(), s.getYearOfStudy(), sem.getSemesterNo());
        ElectiveRule rule = electiveRules.findByCurriculumIdAndYearOfStudyAndSemesterNo(c.getId(), s.getYearOfStudy(), sem.getSemesterNo()).orElse(null);
        Set<Long> chosen = new HashSet<>();
        if (existing != null) existing.getModules().forEach(m -> chosen.add(m.getModule().getId()));
        List<ModuleOption> core = slot.stream().filter(cm -> cm.getCategory() == CurriculumModule.Category.CORE).map(cm -> option(cm, true)).toList();
        List<ModuleOption> electives = slot.stream().filter(cm -> cm.getCategory() == CurriculumModule.Category.ELECTIVE)
                .map(cm -> option(cm, chosen.contains(cm.getModule().getId()))).toList();
        Semester.Window w = sem.window(today);
        String state = switch (w) {
            case NOT_OPEN -> "NOT_OPEN";
            case CLOSED -> "CLOSED";
            default -> slot.isEmpty() ? "NOTHING_TO_REGISTER" : w.name();
        };
        String message = switch (state) {
            case "NOT_OPEN" -> "Registration for " + sem.getName() + " opens on " + sem.getRegistrationOpens() + ".";
            case "CLOSED" -> "Registration for " + sem.getName() + " has closed. Contact Registry if you have not registered.";
            case "LATE" -> "The registration deadline has passed: you can still register until " + sem.getLateRegistrationCloses()
                    + ", and the registration will be marked late" + (sem.getLateRegistrationFee().signum() > 0
                    ? ". A late registration fee of TZS " + tzs(sem.getLateRegistrationFee()) + " will be billed when you submit." : ".");
            case "NOTHING_TO_REGISTER" -> "Your curriculum has no modules for year " + s.getYearOfStudy() + " semester " + sem.getSemesterNo() + ".";
            default -> "Registration is open until " + sem.getRegistrationCloses() + ".";
        };
        return new StudentRegistration(info, state, message, s.getYearOfStudy(), label(c), core, electives, rule == null ? 0 : rule.getMinElectives(),
                rule == null ? 0 : rule.getMaxElectives(), clearance, reg, history);
    }

    @Transactional
    public StudentRegistration submit(Long semesterId, List<Long> electiveIds, boolean detailsConfirmed) {
        Student s = me();
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        LocalDate today = LocalDate.now(ZONE);
        Semester.Window w = sem.window(today);
        if (w == Semester.Window.NOT_OPEN) throw new BusinessRuleException("REGISTRATION_NOT_OPEN", "Registration opens on " + sem.getRegistrationOpens());
        if (w == Semester.Window.CLOSED) throw new BusinessRuleException("REGISTRATION_CLOSED", "Registration for " + sem.getName() + " has closed");
        if (s.getStatus() != Student.Status.ACTIVE) {
            throw new BusinessRuleException("FIRST_REGISTRATION_REQUIRED", "Complete your first registration at Registry before registering for a semester");
        }
        if (!detailsConfirmed) throw new BusinessRuleException("DETAILS_NOT_CONFIRMED", "Confirm that your personal details are correct (or request a correction first)");
        Curriculum c = curricula.curriculumFor(s).orElseThrow(() -> new BusinessRuleException("NO_CURRICULUM", "Your programme's curriculum has not been approved yet"));
        List<CurriculumModule> slot = curriculumModules.findByCurriculumIdAndYearOfStudyAndSemesterNo(c.getId(), s.getYearOfStudy(), sem.getSemesterNo());
        if (slot.isEmpty()) throw new BusinessRuleException("NOTHING_TO_REGISTER", "Your curriculum has no modules for this semester");
        ElectiveRule rule = electiveRules.findByCurriculumIdAndYearOfStudyAndSemesterNo(c.getId(), s.getYearOfStudy(), sem.getSemesterNo()).orElse(null);
        Set<Long> picked = new HashSet<>(electiveIds);
        List<CurriculumModule> electives = slot.stream().filter(cm -> cm.getCategory() == CurriculumModule.Category.ELECTIVE).toList();
        Set<Long> offered = new HashSet<>();
        electives.forEach(cm -> offered.add(cm.getModule().getId()));
        if (!offered.containsAll(picked)) throw new BusinessRuleException("NOT_AN_ELECTIVE", "Choose electives only from those offered for this semester");
        int min = rule == null ? 0 : rule.getMinElectives(), max = rule == null ? 0 : rule.getMaxElectives();
        if (picked.size() < min || picked.size() > max) {
            throw new BusinessRuleException("ELECTIVE_COUNT", min == max ? "Choose exactly " + min + " elective(s)" : "Choose between " + min + " and " + max + " electives");
        }
        Clearance clearance = accounts.evaluate(s, ClearanceGate.Gate.REGISTRATION, sem.getAcademicYear());
        if (clearance.enabled() && !clearance.cleared()) throw new BusinessRuleException("FINANCIAL_CLEARANCE_REQUIRED", clearance.reason());
        if (!health.coveredForRegistration(s.getId(), sem.getAcademicYear().getId())) {
            throw new BusinessRuleException("HEALTH_COVER_REQUIRED", "Health insurance is needed before semester registration: give your NHIF card number, "
                    + "or ask to be registered with NHIF through the institution and pay the contribution (Health insurance in the student portal).");
        }

        SemesterRegistration r = registrations.findByStudentIdAndSemesterId(s.getId(), sem.getId()).orElse(null);
        if (r != null && r.getStatus() == SemesterRegistration.Status.APPROVED) {
            throw new BusinessRuleException("ALREADY_APPROVED", "Your registration for " + sem.getName() + " is already approved. Ask Registry to change modules.");
        }
        if (r == null) {
            r = new SemesterRegistration();
            r.setStudent(s);
            r.setSemester(sem);
        }
        if (s.getCurriculumId() == null) s.setCurriculumId(c.getId()); // the student keeps this curriculum version
        r.setCurriculum(c);
        r.setYearOfStudy(s.getYearOfStudy());
        r.setStatus(SemesterRegistration.Status.SUBMITTED);
        r.setLate(w == Semester.Window.LATE);
        r.setClearance(snapshot(clearance));
        r.setDetailsConfirmed(true);
        r.setSubmittedAt(OffsetDateTime.now());
        r.setSubmittedByName(CurrentUser.name());
        r.setDecidedAt(null);
        r.setDecidedByName(null);
        r.setDecisionNote(null);
        r.getModules().clear();
        if (r.getId() != null) registrations.flush(); // delete before re-inserting the same modules
        BigDecimal total = BigDecimal.ZERO;
        for (CurriculumModule cm : slot) {
            if (cm.getCategory() == CurriculumModule.Category.ELECTIVE && !picked.contains(cm.getModule().getId())) continue;
            ModuleRegistration m = new ModuleRegistration();
            m.setRegistration(r);
            m.setStudentId(s.getId());
            m.setModule(cm.getModule());
            m.setCategory(cm.getCategory());
            m.setCredits(cm.getModule().getCredits());
            r.getModules().add(m);
            total = total.add(m.getCredits());
        }
        r.setTotalCredits(total);
        registrations.save(r);
        if (r.isLate() && sem.getLateRegistrationFee().signum() > 0 && r.getLateFeeInvoiceId() == null) billLateFee(r, s, sem);
        audit.record("SEMESTER_REGISTRATION_SUBMITTED", "SemesterRegistration", r.getId(), Map.of("semester", sem.getName(),
                "modules", r.getModules().size(), "late", r.isLate()));
        return mine();
    }

    @Transactional
    public StudentRegistration cancel(Long registrationId) {
        Student s = me();
        SemesterRegistration r = registrations.findById(registrationId).filter(x -> x.getStudent().getId().equals(s.getId()))
                .orElseThrow(() -> new NotFoundException("Registration", registrationId));
        if (r.getStatus() == SemesterRegistration.Status.APPROVED) throw new BusinessRuleException("ALREADY_APPROVED", "An approved registration can only be changed by Registry");
        r.setStatus(SemesterRegistration.Status.CANCELLED);
        // a late fee not yet paid goes with the registration; one paid (even in part) stays, and a waiver or refund is the bursary's decision
        if (r.getLateFeeInvoiceId() != null && billing.cancelIfUnpaid(r.getLateFeeInvoiceId(), "Semester registration cancelled by the student")) {
            r.setLateFeeInvoiceId(null);
        }
        audit.record("SEMESTER_REGISTRATION_CANCELLED", "SemesterRegistration", r.getId(), Map.of());
        return mine();
    }

    /** Bills the late-registration fee once per registration and asks for its control number (sent to the student by SMS). */
    private void billLateFee(SemesterRegistration r, Student s, Semester sem) {
        var inv = billing.createInvoice(new tz.co.msingi.cims.finance.service.BillingService.NewInvoice(
                tz.co.msingi.cims.finance.domain.Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(), LATE_FEE_PURPOSE,
                r.getId(), "Late registration fee, " + semesterLabel(sem),
                List.of(new tz.co.msingi.cims.finance.service.BillingService.NewInvoice.Line("LATE_REG", "Late registration, " + semesterLabel(sem),
                        sem.getLateRegistrationFee())), sem.getAcademicYear().getId(), null));
        r.setLateFeeInvoiceId(inv.getId());
        billing.ensureControlNumber(inv.getId());
        audit.record("LATE_REGISTRATION_FEE_BILLED", "SemesterRegistration", r.getId(), Map.of("invoiceNo", inv.getInvoiceNo(),
                "amount", sem.getLateRegistrationFee()));
    }

    private LateFee lateFee(SemesterRegistration r) {
        if (r.getLateFeeInvoiceId() == null) return null;
        var inv = billing.get(r.getLateFeeInvoiceId());
        var cn = inv.isPaid() ? null : billing.currentControlNumber(inv.getId());
        return new LateFee(inv.getId(), inv.getInvoiceNo(), inv.getTotalAmount(), inv.balance(), inv.getStatus().name(),
                cn == null ? null : cn.getControlNo());
    }

    private static String tzs(BigDecimal v) {
        return new java.text.DecimalFormat("#,##0").format(v);
    }

    // ------------------------------------------------------------------ Registry

    @Transactional(readOnly = true)
    public SemesterSummary summary(Long semesterId) {
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        Map<String, Long> by = new TreeMap<>();
        var mine = scope.current();
        if (mine.all()) {
            for (Object[] row : registrations.countByStatus(semesterId)) by.put(((SemesterRegistration.Status) row[0]).name(), (Long) row[1]);
        } else {
            registrations.findBySemesterIdOrderBySubmittedAtAsc(semesterId).stream().filter(r -> mine.programme(r.getStudent().getProgramme()))
                    .forEach(r -> by.merge(r.getStatus().name(), 1L, Long::sum));
        }
        long active = students.findByStatusIn(EnumSet.of(Student.Status.ACTIVE)).stream().filter(st -> mine.programme(st.getProgramme())).count();
        long registered = by.getOrDefault("SUBMITTED", 0L) + by.getOrDefault("APPROVED", 0L);
        return new SemesterSummary(sem.getId(), sem.getName(), sem.window(LocalDate.now(ZONE)).name(), active, Math.max(0, active - registered), by);
    }

    @Transactional(readOnly = true)
    public List<RegistrationView> list(Long semesterId, String status) {
        var mine = scope.current();
        return registrations.findBySemesterIdOrderBySubmittedAtAsc(semesterId).stream()
                .filter(r -> mine.programme(r.getStudent().getProgramme()))
                .filter(r -> status == null || status.isBlank() || r.getStatus().name().equals(status))
                .sorted(Comparator.comparing((SemesterRegistration r) -> r.getStatus() == SemesterRegistration.Status.SUBMITTED ? 0 : 1)
                        .thenComparing(SemesterRegistration::getSubmittedAt))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RegistrationView get(Long id) {
        SemesterRegistration r = require(id);
        scope.requireProgramme(r.getStudent().getProgramme().getId());
        return view(r);
    }

    /** Approval re-checks fee clearance as it is now. */
    @Transactional
    public RegistrationView approve(Long id) {
        SemesterRegistration r = require(id);
        approveOne(r);
        return view(r);
    }

    /** Approves every submitted registration whose student is cleared; the others are listed with the reason. */
    @Transactional
    public ApproveAllResult approveAll(Long semesterId) {
        int approved = 0;
        List<String> skipped = new ArrayList<>();
        for (SemesterRegistration r : registrations.findBySemesterIdOrderBySubmittedAtAsc(semesterId)) {
            if (r.getStatus() != SemesterRegistration.Status.SUBMITTED) continue;
            try {
                approveOne(r);
                approved++;
            } catch (BusinessRuleException e) {
                skipped.add(r.getStudent().getRegistrationNo() + ": " + e.getMessage());
            }
        }
        audit.record("SEMESTER_REGISTRATIONS_APPROVED", "Semester", semesterId, Map.of("approved", approved, "skipped", skipped.size()));
        return new ApproveAllResult(approved, skipped);
    }

    private void approveOne(SemesterRegistration r) {
        if (r.getStatus() != SemesterRegistration.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "Only a submitted registration can be approved");
        Clearance c = accounts.evaluate(r.getStudent(), ClearanceGate.Gate.REGISTRATION, r.getSemester().getAcademicYear());
        if (c.enabled() && !c.cleared()) throw new BusinessRuleException("FINANCIAL_CLEARANCE_REQUIRED", c.reason());
        r.setClearance(snapshot(c));
        r.setStatus(SemesterRegistration.Status.APPROVED);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecidedByName(CurrentUser.name());
        audit.record("SEMESTER_REGISTRATION_APPROVED", "SemesterRegistration", r.getId(), Map.of("student", r.getStudent().getRegistrationNo(),
                "semester", r.getSemester().getName()));
        messaging.event("SEMESTER_REGISTRATION_APPROVED", r.getStudent().getPhone(), Map.of("semester", semesterLabel(r.getSemester()),
                "modules", r.getModules().size()));
    }

    @Transactional
    public RegistrationView reject(Long id, String note) {
        SemesterRegistration r = require(id);
        if (r.getStatus() != SemesterRegistration.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "Only a submitted registration can be returned");
        r.setStatus(SemesterRegistration.Status.REJECTED);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecidedByName(CurrentUser.name());
        r.setDecisionNote(note.trim());
        audit.record("SEMESTER_REGISTRATION_REJECTED", "SemesterRegistration", r.getId(), Map.of("note", note.trim()));
        messaging.event("SEMESTER_REGISTRATION_REJECTED", r.getStudent().getPhone(), Map.of("semester", semesterLabel(r.getSemester()), "reason", note.trim()));
        return view(r);
    }

    // ------------------------------------------------------------------ helpers

    /** The semester to register for: one whose registration is open (or late), else the next to open, else the latest. */
    private Semester currentSemester(LocalDate today) {
        List<Semester> all = semesters.findAllByOrderByStartDateDesc();
        return all.stream().filter(s -> s.window(today) == Semester.Window.OPEN || s.window(today) == Semester.Window.LATE)
                .min(Comparator.comparing(Semester::getRegistrationOpens))
                .or(() -> all.stream().filter(s -> s.window(today) == Semester.Window.NOT_OPEN).min(Comparator.comparing(Semester::getRegistrationOpens)))
                .orElse(all.isEmpty() ? null : all.get(0));
    }

    private static Map<String, Object> snapshot(Clearance c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gate", c.gate());
        m.put("enabled", c.enabled());
        m.put("cleared", c.cleared());
        m.put("academicYear", c.academicYear());
        if (c.amountNeeded() != null) m.put("amountNeeded", c.amountNeeded());
        if (c.reason() != null) m.put("reason", c.reason());
        m.put("checkedAt", OffsetDateTime.now().toString());
        return m;
    }

    private static SemesterInfo info(Semester s, LocalDate today) {
        return new SemesterInfo(s.getId(), s.getName(), s.getAcademicYear().getCode(), s.getSemesterNo(), s.getStartDate(), s.getEndDate(),
                s.getRegistrationOpens(), s.getRegistrationCloses(), s.getLateRegistrationCloses(), s.window(today).name(), s.getLateRegistrationFee());
    }

    private static String semesterLabel(Semester s) {
        return s.getName() + " " + s.getAcademicYear().getCode();
    }

    private static ModuleOption option(CurriculumModule cm, boolean selected) {
        return new ModuleOption(cm.getModule().getId(), cm.getModule().getCode(), cm.getModule().getTitle(), cm.getModule().getCredits(),
                cm.getCategory().name(), cm.getModule().getAssessmentType().name(), selected);
    }

    private static String label(Curriculum c) {
        return c.getProgramme().getCode() + " curriculum, version " + c.getCurriculumVersion() + " (from " + c.getEffectiveYear().getCode() + ")";
    }

    private RegistrationView view(SemesterRegistration r) {
        Student s = r.getStudent();
        return new RegistrationView(r.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), s.getProgramme().getName(),
                r.getSemester().getId(), semesterLabel(r.getSemester()), r.getYearOfStudy(), r.getStatus().name(), r.isLate(), r.getTotalCredits(),
                r.getClearance(), r.isDetailsConfirmed(), r.getSubmittedAt(), r.getSubmittedByName(), r.getDecidedAt(), r.getDecidedByName(),
                r.getDecisionNote(), label(r.getCurriculum()),
                r.getModules().stream().sorted(Comparator.comparing((ModuleRegistration m) -> m.getCategory()).thenComparing(m -> m.getModule().getCode()))
                        .map(m -> new RegisteredModule(m.getModule().getId(), m.getModule().getCode(), m.getModule().getTitle(), m.getCredits(),
                                m.getCategory().name(), m.getAttempt().name())).toList(), lateFee(r));
    }

    private SemesterRegistration require(Long id) {
        return registrations.findById(id).orElseThrow(() -> new NotFoundException("Registration", id));
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }
}
