package tz.co.msingi.cims.graduation;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.finance.accounts.AccountDtos;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.graduation.GraduationDtos.AwardView;
import tz.co.msingi.cims.graduation.GraduationDtos.ClearanceSummary;
import tz.co.msingi.cims.graduation.GraduationDtos.ClearanceView;
import tz.co.msingi.cims.graduation.GraduationDtos.Finalist;
import tz.co.msingi.cims.graduation.GraduationDtos.ItemView;
import tz.co.msingi.cims.graduation.GraduationDtos.MyClearance;
import tz.co.msingi.cims.graduation.GraduationDtos.MyGraduation;
import tz.co.msingi.cims.graduation.GraduationDtos.OfficeRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.OfficeView;
import tz.co.msingi.cims.graduation.GraduationDtos.Person;
import tz.co.msingi.cims.graduation.GraduationDtos.QueueLine;
import tz.co.msingi.cims.graduation.GraduationRepositories.ClearanceItemRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.ClearanceOfficeRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.ClearanceRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.GraduandRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.GraduationRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Online clearance of finalists (FR-GRD-01, BR-29, BP-15 steps 1-2, UC-18): the student (or Registry) starts it once final results
 * show the award; each office clears or holds with a reason; finance clears itself from the student's balance; the clearance is
 * complete when every office has cleared.
 */
@Service
@RequiredArgsConstructor
public class ClearanceService {

    static final Role[] REGISTRY = {Role.INSTITUTION_ADMIN, Role.REGISTRAR};
    static final Role[] VIEWERS = {Role.INSTITUTION_ADMIN, Role.REGISTRAR, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER};
    private static final Set<Student.Status> CURRENT = EnumSet.of(Student.Status.ACTIVE, Student.Status.COMPLETED);

    private final ClearanceOfficeRepository offices;
    private final ClearanceRepository clearances;
    private final ClearanceItemRepository items;
    private final GraduandRepository graduands;
    private final GraduationRepository graduations;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final AccountService accounts;
    private final Awards awards;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    // ------------------------------------------------------------------ offices

    /** The clearance offices, with the defaults the first time (Head of Department, library, hostel, sports and games, finance). */
    @Transactional
    public List<OfficeView> offices() {
        ensureDefaults();
        Map<Long, String> names = staffNames();
        return offices.findAllByOrderBySortOrderAscIdAsc().stream().map(o -> officeView(o, names)).toList();
    }

    @Transactional
    public OfficeView saveOffice(Long id, OfficeRequest req) {
        access.require("configure clearance offices", REGISTRY);
        ClearanceOffice o;
        if (id == null) {
            if (offices.findByCodeIgnoreCase(req.code()).isPresent()) throw new BusinessRuleException("DUPLICATE_CODE", "An office with code " + req.code() + " exists");
            o = new ClearanceOffice();
            o.setCode(req.code().toUpperCase(Locale.ROOT));
            o.setKind(req.kind());
        } else {
            o = offices.findById(id).orElseThrow(() -> new NotFoundException("Clearance office", id));
            if (o.getKind() != req.kind() && items.countByOfficeId(o.getId()) > 0) {
                throw new BusinessRuleException("OFFICE_IN_USE", "This office has clearances, so its kind cannot change");
            }
            o.setKind(req.kind());
        }
        if (req.kind() == ClearanceOffice.Kind.FINANCE && offices.findAll().stream()
                .anyMatch(x -> x.getKind() == ClearanceOffice.Kind.FINANCE && x.isActive() && !Objects.equals(x.getId(), o.getId()))) {
            throw new BusinessRuleException("ONE_FINANCE_OFFICE", "There is already an automatic finance office");
        }
        o.setName(req.name().trim());
        o.setInstructions(req.instructions() == null || req.instructions().isBlank() ? null : req.instructions().trim());
        o.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder().shortValue());
        o.setActive(req.active() == null || req.active());
        Set<Long> wanted = req.kind() == ClearanceOffice.Kind.STAFF && req.staffUserIds() != null ? new HashSet<>(req.staffUserIds()) : Set.of();
        List<UserAccount> found = users.findAllById(wanted);
        if (found.size() != wanted.size() || found.stream().anyMatch(u -> u.getUserType() != UserAccount.UserType.STAFF)) {
            throw new BusinessRuleException("UNKNOWN_STAFF", "Choose staff members of this institution");
        }
        o.getStaff().removeIf(s -> !wanted.contains(s.getUserId()));
        Set<Long> have = o.getStaff().stream().map(ClearanceOfficeStaff::getUserId).collect(Collectors.toSet());
        for (Long uid : wanted) {
            if (have.contains(uid)) continue;
            ClearanceOfficeStaff s = new ClearanceOfficeStaff();
            s.setOffice(o);
            s.setUserId(uid);
            o.getStaff().add(s);
        }
        offices.saveAndFlush(o);
        audit.record(id == null ? "CLEARANCE_OFFICE_CREATED" : "CLEARANCE_OFFICE_UPDATED", "ClearanceOffice", o.getId(), Map.of("code", o.getCode(),
                "name", o.getName(), "kind", o.getKind().name(), "active", o.isActive(),
                "staff", found.stream().map(UserAccount::getFullName).sorted().toList()));
        return officeView(o, staffNames());
    }

    /** Active staff who can be made officers of an office. */
    @Transactional(readOnly = true)
    public List<GraduationDtos.StaffOption> staffOptions() {
        access.require("list staff", REGISTRY);
        return users.findAll().stream()
                .filter(u -> u.getUserType() == UserAccount.UserType.STAFF && u.getStatus() == UserAccount.Status.ACTIVE)
                .sorted(Comparator.comparing(UserAccount::getFullName))
                .map(u -> new GraduationDtos.StaffOption(u.getId(), u.getFullName(), u.getRoles().stream().map(r -> r.getRole().name()).distinct().sorted().toList()))
                .toList();
    }

    void ensureDefaults() {
        if (offices.count() > 0) return;
        office("HOD", "Head of Department", ClearanceOffice.Kind.DEPARTMENT, 10, "Return departmental equipment and project reports.");
        office("LIBRARY", "Library", ClearanceOffice.Kind.STAFF, 20, "Return all borrowed books and settle any library fines.");
        office("HOSTEL", "Hostel", ClearanceOffice.Kind.STAFF, 30, "Hand over your room and its inventory to the warden.");
        office("SPORTS", "Sports and games", ClearanceOffice.Kind.STAFF, 40, "Return sports kit and equipment.");
        office("FINANCE", "Finance", ClearanceOffice.Kind.FINANCE, 50, "Cleared automatically when you owe nothing.");
    }

    private void office(String code, String name, ClearanceOffice.Kind kind, int order, String instructions) {
        ClearanceOffice o = new ClearanceOffice();
        o.setCode(code);
        o.setName(name);
        o.setKind(kind);
        o.setSortOrder((short) order);
        o.setInstructions(instructions);
        offices.save(o);
    }

    // ------------------------------------------------------------------ finalists and starting

    /** Students whose published results reach the end of their programme, with their award and clearance (for Registry). */
    @Transactional(readOnly = true)
    public List<Finalist> finalists() {
        access.require("list finalists", VIEWERS);
        List<Student> current = students.findAll().stream().filter(s -> CURRENT.contains(s.getStatus()) || s.getStatus() == Student.Status.GRADUATED).toList();
        Map<Long, Clearance> byStudent = clearances.findByStudentIdIn(current.stream().map(Student::getId).toList()).stream()
                .collect(Collectors.toMap(Clearance::getStudentId, Function.identity()));
        Map<Long, String> gradNames = graduations.findAll().stream().collect(Collectors.toMap(g -> g.getId(), Graduation::getName));
        return current.stream().map(s -> {
            Awards.Outcome o = awards.of(s);
            if (!o.finalist()) return null;
            Clearance c = byStudent.get(s.getId());
            String grad = graduands.findByStudentId(s.getId()).map(g -> gradNames.get(g.getGraduationId())).orElse(null);
            return new Finalist(s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), AwardView.of(o),
                    c == null ? null : c.getStatus().name(), c == null ? null : c.getId(), grad);
        }).filter(Objects::nonNull).sorted(Comparator.comparing(Finalist::registrationNo)).toList();
    }

    @Transactional
    public ClearanceView start(Long studentId) {
        access.require("start a student's clearance", REGISTRY);
        Student s = students.findById(studentId).orElseThrow(() -> new NotFoundException("Student", studentId));
        return view(begin(s), true);
    }

    @Transactional
    public MyClearance startMine() {
        Student s = me();
        begin(s);
        return mine();
    }

    private Clearance begin(Student s) {
        if (clearances.findByStudentId(s.getId()).isPresent()) throw new BusinessRuleException("CLEARANCE_STARTED", "Clearance has already been started");
        if (!CURRENT.contains(s.getStatus())) throw new BusinessRuleException("NOT_A_CURRENT_STUDENT", "Only current students are cleared for graduation");
        Awards.Outcome o = awards.of(s);
        if (!o.qualified()) {
            throw new BusinessRuleException("NOT_A_FINALIST", "Clearance starts once the final results show the award" + (o.reason() == null ? "" : ": " + o.reason()));
        }
        ensureDefaults();
        List<ClearanceOffice> active = offices.findAllByOrderBySortOrderAscIdAsc().stream().filter(ClearanceOffice::isActive).toList();
        if (active.isEmpty()) throw new BusinessRuleException("NO_OFFICES", "No clearance offices are set up");
        Clearance c = new Clearance();
        c.setStudentId(s.getId());
        c.setStartedByName(CurrentUser.name());
        for (ClearanceOffice office : active) {
            ClearanceItem i = new ClearanceItem();
            i.setClearance(c);
            i.setOffice(office);
            c.getItems().add(i);
        }
        clearances.saveAndFlush(c);
        audit.record("CLEARANCE_STARTED", "Clearance", c.getId(), Map.of("student", s.getRegistrationNo(), "offices", active.size()));
        refreshFinance(c, s);
        return c;
    }

    // ------------------------------------------------------------------ reading

    @Transactional
    public List<ClearanceSummary> list() {
        access.require("list clearances", VIEWERS);
        List<Clearance> all = clearances.findAllByOrderByStartedAtDesc();
        Map<Long, Student> ss = students.findAllById(all.stream().map(Clearance::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        return all.stream().map(c -> {
            refreshFinance(c, ss.get(c.getStudentId()));
            Student s = ss.get(c.getStudentId());
            return new ClearanceSummary(c.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), c.getStatus().name(),
                    c.getStartedAt(), c.getClearedAt(), count(c, ClearanceItem.Status.CLEARED), count(c, ClearanceItem.Status.HELD), c.getItems().size());
        }).toList();
    }

    @Transactional
    public ClearanceView get(Long id) {
        Clearance c = clearances.findById(id).orElseThrow(() -> new NotFoundException("Clearance", id));
        Student s = students.findById(c.getStudentId()).orElseThrow();
        boolean officer = c.getItems().stream().anyMatch(i -> canDecide(i.getOffice(), s));
        if (!access.has(VIEWERS) && !officer) throw new AccessDeniedException("Not allowed to see this clearance");
        refreshFinance(c, s);
        return view(c, false);
    }

    /** An office's clearances, oldest first, for its officers (the Head of Department sees the students of their department). */
    @Transactional
    public List<QueueLine> queue(Long officeId) {
        ClearanceOffice o = offices.findById(officeId).orElseThrow(() -> new NotFoundException("Clearance office", officeId));
        boolean wide = access.has(VIEWERS) || access.has(Role.BURSAR, Role.ACCOUNTANT) && o.getKind() == ClearanceOffice.Kind.FINANCE;
        boolean staff = o.getKind() == ClearanceOffice.Kind.STAFF && isStaff(o);
        boolean hod = o.getKind() == ClearanceOffice.Kind.DEPARTMENT && access.has(Role.HEAD_OF_DEPARTMENT);
        if (!wide && !staff && !hod) throw new AccessDeniedException("Not an officer of " + o.getName());
        List<ClearanceItem> list = items.findByOffice(o.getId());
        Map<Long, Student> ss = students.findAllById(list.stream().map(i -> i.getClearance().getStudentId()).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        if (o.getKind() == ClearanceOffice.Kind.FINANCE) list.forEach(i -> refreshFinance(i.getClearance(), ss.get(i.getClearance().getStudentId())));
        return list.stream()
                .filter(i -> wide || staff || canDecide(o, ss.get(i.getClearance().getStudentId())))
                .map(i -> {
                    Student s = ss.get(i.getClearance().getStudentId());
                    return new QueueLine(i.getId(), i.getClearance().getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(),
                            s.getProgramme().getDepartment() == null ? null : s.getProgramme().getDepartment().getName(), i.getStatus().name(), i.getNote(),
                            i.getDecidedByName(), i.getDecidedAt(), i.getClearance().getStartedAt(), canDecide(o, s) && editable(i.getClearance()));
                }).toList();
    }

    @Transactional
    public MyClearance mine() {
        Student s = me();
        Awards.Outcome o = awards.of(s);
        Clearance c = clearances.findByStudentId(s.getId()).orElse(null);
        if (c != null) refreshFinance(c, s);
        MyGraduation g = graduands.findByStudentId(s.getId()).flatMap(gr -> graduations.findById(gr.getGraduationId())
                .map(x -> new MyGraduation(x.getName(), x.getCeremonyDate(), x.getStatus().name(), gr.getAwardName(), gr.getClassification()))).orElse(null);
        return new MyClearance(AwardView.of(o), c == null ? null : view(c, false), g);
    }

    // ------------------------------------------------------------------ deciding (BP-15 step 2)

    @Transactional
    public ItemView decide(Long itemId, ClearanceItem.Status status, String note) {
        ClearanceItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("Clearance item", itemId));
        Clearance c = i.getClearance();
        Student s = students.findById(c.getStudentId()).orElseThrow();
        if (i.getOffice().getKind() == ClearanceOffice.Kind.FINANCE) {
            throw new BusinessRuleException("FINANCE_AUTOMATIC", "Finance clears automatically once the student owes nothing");
        }
        if (!canDecide(i.getOffice(), s)) throw new AccessDeniedException("Only " + i.getOffice().getName() + " officers decide this");
        if (!editable(c)) throw new BusinessRuleException("GRADUAND_LISTED", "The student is on a graduand list sent to the Governing Board");
        if (status == ClearanceItem.Status.PENDING) throw new BusinessRuleException("INVALID_DECISION", "Clear or hold");
        String n = note == null || note.isBlank() ? null : note.trim();
        if (status == ClearanceItem.Status.HELD && n == null) throw new BusinessRuleException("REASON_REQUIRED", "Say why the student is held and what they must do");
        i.setStatus(status);
        i.setNote(n);
        i.setDecidedByName(CurrentUser.name());
        i.setDecidedAt(OffsetDateTime.now());
        audit.record(status == ClearanceItem.Status.CLEARED ? "CLEARANCE_CLEARED" : "CLEARANCE_HELD", "Clearance", c.getId(),
                Map.of("office", i.getOffice().getCode(), "student", s.getRegistrationNo(), "note", String.valueOf(n)));
        if (status == ClearanceItem.Status.HELD) {
            messaging.event("CLEARANCE_HELD", s.getPhone(), Map.of("office", i.getOffice().getName(), "reason", n));
        }
        settle(c, s);
        return itemView(i, s);
    }

    /** Finance clears itself when the student owes nothing (and meets the graduation fees gate, where enabled). */
    void refreshFinance(Clearance c, Student s) {
        if (c.getStatus() == Clearance.Status.CLEARED && !editable(c)) return;
        boolean changed = false;
        for (ClearanceItem i : c.getItems()) {
            if (i.getOffice().getKind() != ClearanceOffice.Kind.FINANCE) continue;
            BigDecimal balance = accounts.forStudent(s.getId()).balance();
            AccountDtos.Clearance gate = accounts.evaluate(s, ClearanceGate.Gate.GRADUATION);
            boolean ok = balance.signum() <= 0 && (!gate.enabled() || gate.cleared());
            String note = ok ? null : balance.signum() > 0 ? "TZS " + tzs(balance) + " outstanding. Pay it with a control number from the Fees page."
                    : gate.reason();
            ClearanceItem.Status st = ok ? ClearanceItem.Status.CLEARED : ClearanceItem.Status.HELD;
            if (i.getStatus() != st || !Objects.equals(i.getNote(), note)) {
                i.setStatus(st);
                i.setNote(note);
                i.setDecidedByName("Automatic (fees balance)");
                i.setDecidedAt(OffsetDateTime.now());
                changed = true;
            }
        }
        if (changed) settle(c, s);
    }

    private void settle(Clearance c, Student s) {
        boolean all = c.getItems().stream().allMatch(x -> x.getStatus() == ClearanceItem.Status.CLEARED);
        if (all && c.getStatus() != Clearance.Status.CLEARED) {
            c.setStatus(Clearance.Status.CLEARED);
            c.setClearedAt(OffsetDateTime.now());
            audit.record("CLEARANCE_COMPLETED", "Clearance", c.getId(), Map.of("student", s.getRegistrationNo()));
            messaging.event("CLEARANCE_COMPLETE", s.getPhone(), Map.of());
        } else if (!all && c.getStatus() == Clearance.Status.CLEARED) {
            c.setStatus(Clearance.Status.IN_PROGRESS);
            c.setClearedAt(null);
        }
    }

    // ------------------------------------------------------------------ access and views

    /** Whether the signed-in user decides this office's clearance of this student. */
    boolean canDecide(ClearanceOffice o, Student s) {
        return switch (o.getKind()) {
            case FINANCE -> false;
            case DEPARTMENT -> s != null && s.getProgramme().getDepartment() != null && access.headsDepartment(s.getProgramme().getDepartment().getId());
            case STAFF -> isStaff(o);
        };
    }

    private boolean isStaff(ClearanceOffice o) {
        Long me = CurrentUser.id().orElse(null);
        return me != null && o.getStaff().stream().anyMatch(x -> x.getUserId().equals(me));
    }

    /** Decisions can change until the student is on a graduand list sent to (or approved by) the Governing Board. */
    boolean editable(Clearance c) {
        return graduands.findByStudentId(c.getStudentId()).flatMap(g -> graduations.findById(g.getGraduationId()))
                .map(g -> g.getStatus() == Graduation.Status.DRAFT).orElse(true);
    }

    private ClearanceView view(Clearance c, boolean fresh) {
        Student s = students.findById(c.getStudentId()).orElseThrow();
        List<ItemView> iv = c.getItems().stream().sorted(Comparator.comparing((ClearanceItem i) -> i.getOffice().getSortOrder()).thenComparing(i -> i.getOffice().getId()))
                .map(i -> itemView(i, s)).toList();
        return new ClearanceView(c.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), s.getProgramme().getName(),
                c.getStatus().name(), c.getStartedByName(), c.getStartedAt(), c.getClearedAt(), count(c, ClearanceItem.Status.CLEARED), c.getItems().size(), iv);
    }

    private ItemView itemView(ClearanceItem i, Student s) {
        ClearanceOffice o = i.getOffice();
        return new ItemView(i.getId(), o.getId(), o.getCode(), o.getName(), o.getKind().name(), o.getInstructions(), i.getStatus().name(), i.getNote(),
                i.getDecidedByName(), i.getDecidedAt(), CurrentUser.id().isPresent() && canDecide(o, s) && editable(i.getClearance()));
    }

    private OfficeView officeView(ClearanceOffice o, Map<Long, String> names) {
        List<ClearanceItem> its = items.findByOffice(o.getId());
        boolean decide = switch (o.getKind()) {
            case FINANCE -> false;
            case DEPARTMENT -> access.has(Role.HEAD_OF_DEPARTMENT);
            case STAFF -> isStaff(o);
        };
        return new OfficeView(o.getId(), o.getCode(), o.getName(), o.getKind().name(), o.getInstructions(), o.getSortOrder(), o.isActive(),
                o.getStaff().stream().map(x -> new Person(x.getUserId(), names.getOrDefault(x.getUserId(), "—"))).sorted(Comparator.comparing(Person::fullName)).toList(),
                its.stream().filter(i -> i.getStatus() == ClearanceItem.Status.PENDING).count(),
                its.stream().filter(i -> i.getStatus() == ClearanceItem.Status.HELD).count(), decide);
    }

    private Map<Long, String> staffNames() {
        Set<Long> ids = offices.findAll().stream().flatMap(o -> o.getStaff().stream().map(ClearanceOfficeStaff::getUserId)).collect(Collectors.toSet());
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static int count(Clearance c, ClearanceItem.Status st) {
        return (int) c.getItems().stream().filter(i -> i.getStatus() == st).count();
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    static String tzs(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", a.longValue()) : String.format(Locale.ENGLISH, "%,.2f", a);
    }
}
