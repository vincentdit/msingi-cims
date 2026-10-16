package tz.co.msingi.cims.hostel;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.hostel.HostelDtos.AllocateResult;
import tz.co.msingi.cims.hostel.HostelDtos.AllocationView;
import tz.co.msingi.cims.hostel.HostelDtos.ApplicationView;
import tz.co.msingi.cims.hostel.HostelDtos.ApplyRequest;
import tz.co.msingi.cims.hostel.HostelDtos.CheckOutRequest;
import tz.co.msingi.cims.hostel.HostelDtos.FreeBed;
import tz.co.msingi.cims.hostel.HostelDtos.HostelOption;
import tz.co.msingi.cims.hostel.HostelDtos.MyHostel;
import tz.co.msingi.cims.hostel.HostelDtos.ReleaseResult;
import tz.co.msingi.cims.hostel.HostelDtos.WindowRequest;
import tz.co.msingi.cims.hostel.HostelDtos.WindowView;
import tz.co.msingi.cims.hostel.HostelRepositories.AllocationRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.ApplicationRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.BedRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.HostelRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.WindowRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Hostel applications and allocation (FR-HST-02..05, BP-12, UC-16): windows, student applications with preferences, automatic
 * allocation by priority (special needs, first years, fees paid, then first come) or manual allocation, never two students on a bed
 * and never against the room's gender; the hostel fee invoiced with a control number; unpaid allocations released after the payment
 * period and the waiting list offered the beds; check-in once paid, check-out with condition, damages and a damages invoice.
 */
@Service
@RequiredArgsConstructor
public class AllocationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    public static final String PURPOSE = "HOSTEL_FEES";
    public static final String DAMAGES_PURPOSE = "HOSTEL_DAMAGES";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final WindowRepository windows;
    private final ApplicationRepository applications;
    private final AllocationRepository allocations;
    private final BedRepository beds;
    private final HostelRepository hostels;
    private final AcademicYearRepository years;
    private final StudentRepository students;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final AccountService accounts;
    private final InventoryService inventory;
    private final ExamAccess access;
    private final HostelScope scope;
    private final Messaging messaging;
    private final AuditService audit;
    private final BedChangeService bedChanges;

    // ------------------------------------------------------------------ windows

    @Transactional(readOnly = true)
    public List<WindowView> windows() {
        access.require("see hostel windows", InventoryService.VIEWERS);
        return windows.findAllByOrderByOpensOnDesc().stream().map(this::windowView).toList();
    }

    @Transactional
    public WindowView saveWindow(Long id, WindowRequest req) {
        access.require("manage hostel windows", InventoryService.WARDENS);
        scope.requireAll("open hostel application windows");
        if (req.closesOn().isBefore(req.opensOn())) throw new BusinessRuleException("INVALID_DATES", "The window must close after it opens");
        AcademicYear y = years.findById(req.academicYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.academicYearId()));
        HostelWindow w = id == null ? new HostelWindow() : windows.findById(id).orElseThrow(() -> new NotFoundException("Hostel window", id));
        if (id == null) w.setCreatedByName(CurrentUser.name());
        w.setAcademicYear(y);
        w.setName(req.name().trim());
        w.setOpensOn(req.opensOn());
        w.setClosesOn(req.closesOn());
        w.setOccupancyStarts(req.occupancyStarts());
        w.setOccupancyEnds(req.occupancyEnds());
        w.setPaymentDays(req.paymentDays().shortValue());
        w.setFirstYearsFirst(req.firstYearsFirst() == null || req.firstYearsFirst());
        w.setSpecialNeedsFirst(req.specialNeedsFirst() == null || req.specialNeedsFirst());
        w.setPaidFeesFirst(req.paidFeesFirst() == null || req.paidFeesFirst());
        windows.saveAndFlush(w);
        audit.record(id == null ? "HOSTEL_WINDOW_CREATED" : "HOSTEL_WINDOW_UPDATED", "HostelWindow", w.getId(), Map.of("name", w.getName(),
                "opens", w.getOpensOn().toString(), "closes", w.getClosesOn().toString()));
        return windowView(w);
    }

    // ------------------------------------------------------------------ student (FR-HST-02, FR-STU-04)

    @Transactional(readOnly = true)
    public MyHostel mine() {
        Student s = me();
        List<WindowView> open = windows.findAllByOrderByOpensOnDesc().stream().filter(this::isOpen).map(this::windowView).toList();
        HostelApplication current = applications.findByStudentIdOrderBySubmittedAtDesc(s.getId()).stream()
                .filter(a -> a.getStatus() != HostelApplication.Status.CANCELLED).findFirst().orElse(null);
        List<HostelOption> options = hostels.findAllByOrderByNameAsc().stream().filter(Hostel::isActive)
                .filter(h -> h.getGender() == Hostel.Gender.MIXED || h.getGender().name().equals(gender(s)))
                .map(h -> new HostelOption(h.getId(), h.getName(), h.getGender().name())).toList();
        List<AllocationView> history = allocations.findByStudent(s.getId()).stream().map(a -> allocationView(a, s)).toList();
        return new MyHostel(s.getSex(), open, options, current == null ? null : applicationView(current, s, null, false), history, blocker(s));
    }

    @Transactional
    public MyHostel apply(ApplyRequest req) {
        Student s = me();
        String why = blocker(s);
        if (why != null) throw new BusinessRuleException("CANNOT_APPLY", why);
        HostelWindow w = windows.findById(req.windowId()).orElseThrow(() -> new NotFoundException("Hostel window", req.windowId()));
        if (!isOpen(w)) throw new BusinessRuleException("WINDOW_CLOSED", "Hostel applications for " + w.getName() + " are not open");
        HostelApplication a = applications.findByWindowIdAndStudentId(w.getId(), s.getId()).orElse(null);
        if (a != null && a.getStatus() != HostelApplication.Status.SUBMITTED && a.getStatus() != HostelApplication.Status.WAITLISTED
                && a.getStatus() != HostelApplication.Status.CANCELLED) {
            throw new BusinessRuleException("ALREADY_DECIDED", "Your application has already been decided");
        }
        List<Long> prefs = Stream.of(req.preference1Id(), req.preference2Id(), req.preference3Id()).filter(Objects::nonNull).toList();
        if (new HashSet<>(prefs).size() != prefs.size()) throw new BusinessRuleException("DUPLICATE_PREFERENCE", "Choose each hostel once");
        for (Long hid : prefs) {
            Hostel h = hostels.findById(hid).orElseThrow(() -> new NotFoundException("Hostel", hid));
            if (!h.isActive() || (h.getGender() != Hostel.Gender.MIXED && !h.getGender().name().equals(gender(s)))) {
                throw new BusinessRuleException("WRONG_HOSTEL", h.getName() + " is not open to you");
            }
        }
        if (a == null) {
            a = new HostelApplication();
            a.setWindow(w);
            a.setStudentId(s.getId());
        }
        a.setStatus(HostelApplication.Status.SUBMITTED);
        a.setCancelledAt(null);
        a.setPreference1Id(prefs.size() > 0 ? prefs.get(0) : null);
        a.setPreference2Id(prefs.size() > 1 ? prefs.get(1) : null);
        a.setPreference3Id(prefs.size() > 2 ? prefs.get(2) : null);
        a.setRoomType(req.roomType());
        a.setSpecialNeeds(Boolean.TRUE.equals(req.specialNeeds()));
        a.setSpecialNeedsNote(a.isSpecialNeeds() && req.specialNeedsNote() != null && !req.specialNeedsNote().isBlank() ? req.specialNeedsNote().trim() : null);
        applications.saveAndFlush(a);
        audit.record("HOSTEL_APPLIED", "HostelApplication", a.getId(), Map.of("student", s.getRegistrationNo(), "window", w.getName()));
        return mine();
    }

    @Transactional
    public MyHostel cancelMine(Long applicationId) {
        Student s = me();
        HostelApplication a = applications.findById(applicationId).filter(x -> x.getStudentId().equals(s.getId()))
                .orElseThrow(() -> new NotFoundException("Hostel application", applicationId));
        if (a.getStatus() != HostelApplication.Status.SUBMITTED && a.getStatus() != HostelApplication.Status.WAITLISTED) {
            throw new BusinessRuleException("ALREADY_DECIDED", "A bed has been allocated. Ask the warden to cancel it.");
        }
        a.setStatus(HostelApplication.Status.CANCELLED);
        a.setCancelledAt(OffsetDateTime.now());
        audit.record("HOSTEL_APPLICATION_CANCELLED", "HostelApplication", a.getId(), Map.of("student", s.getRegistrationNo(), "by", "student"));
        return mine();
    }

    // ------------------------------------------------------------------ warden: applications and allocation (FR-HST-03)

    @Transactional(readOnly = true)
    public List<ApplicationView> applications(Long windowId) {
        access.require("see hostel applications", InventoryService.VIEWERS);
        HostelWindow w = windows.findById(windowId).orElseThrow(() -> new NotFoundException("Hostel window", windowId));
        List<HostelApplication> list = applications.findByWindowIdOrderBySubmittedAtAsc(w.getId());
        Map<Long, Student> ss = studentsOf(list);
        Map<Long, HostelAllocation> latest = new HashMap<>();
        for (HostelAllocation al : allocations.findByWindow(w.getId())) {
            HostelAllocation have = latest.get(al.getApplication().getId());
            if (have == null || al.getAllocatedAt().isAfter(have.getAllocatedAt())) latest.put(al.getApplication().getId(), al);
        }
        Map<Long, Boolean> cleared = new HashMap<>();
        return ordered(w, list, ss, cleared).stream().map(a -> applicationView(a, ss.get(a.getStudentId()), latest.get(a.getId()),
                cleared.getOrDefault(a.getStudentId(), false))).toList();
    }

    /** Allocates every waiting application of a window in priority order; applicants with no suitable bed go on the waiting list. */
    @Transactional
    public AllocateResult allocate(Long windowId) {
        access.require("allocate hostel beds", InventoryService.WARDENS);
        scope.requireAll("run automatic allocation");
        HostelWindow w = windows.findById(windowId).orElseThrow(() -> new NotFoundException("Hostel window", windowId));
        return runAllocation(w, "AUTO");
    }

    AllocateResult runAllocation(HostelWindow w, String by) {
        List<HostelAllocation> taken = allocations.findByStatusIn(java.util.EnumSet.of(HostelAllocation.Status.ALLOCATED, HostelAllocation.Status.CHECKED_IN));
        Set<Long> busy = taken.stream().map(a -> a.getBed().getId()).collect(Collectors.toCollection(HashSet::new));
        List<HostelBed> free = beds.findAllWithRooms().stream().filter(InventoryService::usable).filter(b -> !busy.contains(b.getId()))
                .sorted(Comparator.comparing((HostelBed b) -> b.getRoom().getHostel().getName()).thenComparing(b -> b.getRoom().getBlock())
                        .thenComparing(b -> b.getRoom().getFloor()).thenComparing(b -> b.getRoom().getRoomNo()).thenComparing(HostelBed::getLabel))
                .collect(Collectors.toCollection(ArrayList::new));
        List<HostelApplication> waiting = applications.findByWindowIdOrderBySubmittedAtAsc(w.getId()).stream()
                .filter(a -> a.getStatus() == HostelApplication.Status.SUBMITTED || a.getStatus() == HostelApplication.Status.WAITLISTED).toList();
        Map<Long, Student> ss = studentsOf(waiting);
        int allocated = 0, waitlisted = 0;
        List<String> notes = new ArrayList<>();
        for (HostelApplication a : ordered(w, waiting, ss, new HashMap<>())) {
            Student s = ss.get(a.getStudentId());
            if (s.getStatus() != Student.Status.ACTIVE) {
                notes.add(s.getRegistrationNo() + ": not an active student");
                continue;
            }
            HostelBed bed = choose(a, s, free);
            if (bed == null) {
                if (a.getStatus() != HostelApplication.Status.WAITLISTED) {
                    a.setStatus(HostelApplication.Status.WAITLISTED);
                    messaging.event("HOSTEL_WAITLISTED", s.getPhone(), Map.of("window", w.getName()));
                }
                waitlisted++;
                continue;
            }
            free.remove(bed);
            assign(w, a, s, bed, HostelAllocation.Method.AUTO);
            allocated++;
        }
        audit.record("HOSTEL_ALLOCATION_RUN", "HostelWindow", w.getId(), Map.of("allocated", allocated, "waitlisted", waitlisted, "by", by));
        return new AllocateResult(allocated, waitlisted, notes);
    }

    @Transactional(readOnly = true)
    public List<FreeBed> freeBeds(Long applicationId) {
        access.require("allocate hostel beds", InventoryService.WARDENS);
        HostelApplication a = applications.findById(applicationId).orElseThrow(() -> new NotFoundException("Hostel application", applicationId));
        Student s = students.findById(a.getStudentId()).orElseThrow();
        Set<Long> busy = inventory.activeByBed().keySet();
        return beds.findAllWithRooms().stream().filter(InventoryService::usable).filter(b -> !busy.contains(b.getId()))
                .filter(b -> scope.covers(b.getRoom().getHostel().getId()))
                .filter(b -> b.getRoom().getGender().name().equals(gender(s)))
                .sorted(Comparator.comparing((HostelBed b) -> preferenceRank(a, b.getRoom().getHostel().getId())).thenComparing(b -> b.getRoom().getHostel().getName())
                        .thenComparing(b -> b.getRoom().getRoomNo()).thenComparing(HostelBed::getLabel))
                .map(b -> new FreeBed(b.getId(), b.getRoom().getHostel().getName(), b.getRoom().label(), b.getLabel(), b.getRoom().getRoomType().name(),
                        b.getRoom().getGender().name(), b.getRoom().isAccessible(), b.getRoom().getFeeCategory().getAmount()))
                .toList();
    }

    @Transactional
    public AllocationView allocateManually(Long applicationId, Long bedId) {
        access.require("allocate hostel beds", InventoryService.WARDENS);
        HostelApplication a = applications.findById(applicationId).orElseThrow(() -> new NotFoundException("Hostel application", applicationId));
        if (a.getStatus() != HostelApplication.Status.SUBMITTED && a.getStatus() != HostelApplication.Status.WAITLISTED) {
            throw new BusinessRuleException("ALREADY_DECIDED", "This application already has a bed or was cancelled");
        }
        Student s = students.findById(a.getStudentId()).orElseThrow();
        HostelBed b = beds.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        scope.require(b.getRoom().getHostel().getId());
        if (!b.getRoom().getGender().name().equals(gender(s))) {
            throw new BusinessRuleException("GENDER_MISMATCH", b.getRoom().label() + " is designated for " + b.getRoom().getGender().name().toLowerCase() + " students");
        }
        if (!InventoryService.usable(b)) throw new BusinessRuleException("BED_UNAVAILABLE", "That bed is out of service");
        if (inventory.activeByBed().containsKey(b.getId())) throw new BusinessRuleException("BED_TAKEN", "That bed is already allocated");
        return allocationView(assign(a.getWindow(), a, s, b, HostelAllocation.Method.MANUAL), s);
    }

    private HostelAllocation assign(HostelWindow w, HostelApplication a, Student s, HostelBed bed, HostelAllocation.Method method) {
        HostelAllocation al = new HostelAllocation();
        al.setApplication(a);
        al.setWindowId(w.getId());
        al.setStudentId(s.getId());
        al.setBed(bed);
        al.setFee(bed.getRoom().getFeeCategory().getAmount());
        al.setPayBy(LocalDate.now(ZONE).plusDays(w.getPaymentDays()));
        al.setMethod(method);
        al.setAllocatedByName(CurrentUser.id().isPresent() ? CurrentUser.name() : "System");
        try {
            allocations.saveAndFlush(al);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("BED_TAKEN", "That bed was just allocated to someone else. Try again.");
        }
        a.setStatus(HostelApplication.Status.ALLOCATED);
        String where = bed.getRoom().getHostel().getName() + ", " + bed.getRoom().label() + ", bed " + bed.getLabel();
        String controlNo = null;
        if (al.getFee().signum() > 0) {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(),
                    PURPOSE, al.getId(), "Hostel accommodation " + w.getAcademicYear().getCode() + ": " + where,
                    List.of(new BillingService.NewInvoice.Line("HOSTEL", "Hostel accommodation (" + bed.getRoom().getFeeCategory().getName() + ")", al.getFee())),
                    w.getAcademicYear().getId(), null));
            al.setInvoiceId(inv.getId());
            ControlNumber cn = billing.ensureControlNumber(inv.getId());
            controlNo = cn == null ? null : cn.getControlNo();
        }
        audit.record("HOSTEL_BED_ALLOCATED", "HostelAllocation", al.getId(), Map.of("student", s.getRegistrationNo(), "bed", where,
                "method", method.name(), "fee", al.getFee()));
        messaging.event("HOSTEL_ALLOCATED", s.getPhone(), Map.of("place", where, "amount", "TZS " + tzs(al.getFee()),
                "deadline", DATE.format(al.getPayBy()), "controlNo", controlNo == null ? "—" : controlNo));
        return al;
    }

    // ------------------------------------------------------------------ releasing unpaid allocations (FR-HST-04)

    /** Releases allocations still unpaid after their payment date, then offers the freed beds to the waiting lists of open windows. */
    @Transactional
    public ReleaseResult releaseUnpaid() {
        if (CurrentUser.id().isPresent()) {
            access.require("release unpaid hostel allocations", InventoryService.WARDENS);
            scope.requireAll("release unpaid allocations");
        }
        LocalDate today = LocalDate.now(ZONE);
        int released = 0;
        Set<Long> windowIds = new LinkedHashSet<>();
        for (HostelAllocation al : allocations.findOverdue(HostelAllocation.Status.ALLOCATED, today)) {
            Invoice inv = al.getInvoiceId() == null ? null : invoices.findById(al.getInvoiceId()).orElse(null);
            if (inv == null || inv.getPaidAmount().signum() > 0) continue;           // free beds and part-paid ones are kept
            release(al, inv, "Hostel fee not paid by " + DATE.format(al.getPayBy()));
            windowIds.add(al.getWindowId());
            released++;
        }
        int reallocated = 0;
        for (Long wid : windowIds) {
            HostelWindow w = windows.findById(wid).orElseThrow();
            if (w.getOccupancyEnds() != null && w.getOccupancyEnds().isBefore(today)) continue;
            reallocated += runAllocation(w, "RELEASE").allocated();
        }
        if (released > 0) audit.record("HOSTEL_UNPAID_RELEASED", "HostelAllocation", null, Map.of("released", released, "reallocated", reallocated));
        return new ReleaseResult(released, reallocated);
    }

    @Transactional
    public AllocationView cancel(Long allocationId, String reason) {
        access.require("cancel hostel allocations", InventoryService.WARDENS);
        HostelAllocation al = require(allocationId);
        scope.require(al.getBed().getRoom().getHostel().getId());
        if (al.getStatus() != HostelAllocation.Status.ALLOCATED) {
            throw new BusinessRuleException("NOT_CANCELLABLE", "Only allocations not yet checked in can be cancelled; check the student out instead");
        }
        Invoice inv = al.getInvoiceId() == null ? null : invoices.findById(al.getInvoiceId()).orElse(null);
        if (inv != null && inv.getPaidAmount().signum() > 0) {
            // paid (in part or in full): the bed is freed and what was paid is offered back as a refund for the bursary to decide
            bedChanges.settleCancelled(al, "Hostel allocation cancelled before check-in: " + reason.trim());
        }
        release(al, inv, reason.trim());
        al.setStatus(HostelAllocation.Status.CANCELLED);
        return allocationView(al, students.findById(al.getStudentId()).orElseThrow());
    }

    private void release(HostelAllocation al, Invoice inv, String reason) {
        al.setStatus(HostelAllocation.Status.RELEASED);
        al.setReleasedAt(OffsetDateTime.now());
        al.setReleaseReason(reason);
        al.getApplication().setStatus(HostelApplication.Status.RELEASED);
        if (inv != null && inv.getStatus() == Invoice.Status.ISSUED) {
            inv.setStatus(Invoice.Status.CANCELLED);
            ControlNumber cn = billing.currentControlNumber(inv.getId());
            if (cn != null && cn.getStatus() == ControlNumber.Status.ACTIVE) cn.setStatus(ControlNumber.Status.EXPIRED);
        }
        Student s = students.findById(al.getStudentId()).orElseThrow();
        messaging.event("HOSTEL_RELEASED", s.getPhone(), Map.of("reason", reason));
        audit.record("HOSTEL_ALLOCATION_RELEASED", "HostelAllocation", al.getId(), Map.of("student", s.getRegistrationNo(), "reason", reason));
    }

    // ------------------------------------------------------------------ check-in and check-out (FR-HST-05)

    @Transactional
    public AllocationView checkIn(Long allocationId, String condition) {
        access.require("check students in", InventoryService.WARDENS);
        HostelAllocation al = require(allocationId);
        scope.require(al.getBed().getRoom().getHostel().getId());
        if (al.getStatus() != HostelAllocation.Status.ALLOCATED) throw new BusinessRuleException("NOT_ALLOCATED", "This allocation is not waiting for check-in");
        Invoice inv = al.getInvoiceId() == null ? null : invoices.findById(al.getInvoiceId()).orElse(null);
        if (inv != null && inv.getStatus() != Invoice.Status.PAID) {
            throw new BusinessRuleException("HOSTEL_FEE_UNPAID", "The hostel fee (invoice " + inv.getInvoiceNo() + ") is not fully paid: TZS " + tzs(inv.balance()) + " outstanding");
        }
        al.setStatus(HostelAllocation.Status.CHECKED_IN);
        al.setCheckedInAt(OffsetDateTime.now());
        al.setCheckedInByName(CurrentUser.name());
        al.setCheckInCondition(condition == null || condition.isBlank() ? null : condition.trim());
        Student s = students.findById(al.getStudentId()).orElseThrow();
        audit.record("HOSTEL_CHECKED_IN", "HostelAllocation", al.getId(), Map.of("student", s.getRegistrationNo(), "condition", String.valueOf(al.getCheckInCondition())));
        return allocationView(al, s);
    }

    @Transactional
    public AllocationView checkOut(Long allocationId, CheckOutRequest req) {
        access.require("check students out", InventoryService.WARDENS);
        HostelAllocation al = require(allocationId);
        scope.require(al.getBed().getRoom().getHostel().getId());
        if (al.getStatus() != HostelAllocation.Status.CHECKED_IN) throw new BusinessRuleException("NOT_CHECKED_IN", "The student has not checked in");
        BigDecimal charge = req.damageCharge() == null ? BigDecimal.ZERO : req.damageCharge();
        String damages = req.damages() == null || req.damages().isBlank() ? null : req.damages().trim();
        if (charge.signum() > 0 && damages == null) throw new BusinessRuleException("DAMAGES_REQUIRED", "Describe the damage being charged");
        Student s = students.findById(al.getStudentId()).orElseThrow();
        al.setStatus(HostelAllocation.Status.CHECKED_OUT);
        al.setCheckedOutAt(OffsetDateTime.now());
        al.setCheckedOutByName(CurrentUser.name());
        al.setCheckOutCondition(req.condition() == null || req.condition().isBlank() ? null : req.condition().trim());
        al.setDamages(damages);
        al.setDamageCharge(charge.signum() > 0 ? charge : null);
        if (charge.signum() > 0) {
            HostelBed b = al.getBed();
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(),
                    DAMAGES_PURPOSE, al.getId(), "Hostel damages: " + b.getRoom().getHostel().getName() + ", " + b.getRoom().label(),
                    List.of(new BillingService.NewInvoice.Line("HOSTEL_DAMAGE", damages, charge)), null, null));
            al.setDamageInvoiceId(inv.getId());
            billing.ensureControlNumber(inv.getId());
        }
        if (Boolean.TRUE.equals(req.refundUnused())) {
            var refund = bedChanges.refundUnused(al, LocalDate.now(ZONE).plusDays(1), "Checked out early on " + DATE.format(LocalDate.now(ZONE))
                    + ": the unused part of the hostel fee", tz.co.msingi.cims.finance.adjustments.FinanceAdjustment.Origin.HOSTEL_CHECKOUT);
            if (refund == null) throw new BusinessRuleException("NOTHING_TO_REFUND", "Nothing paid is left to refund for the rest of the occupancy period");
        }
        audit.record("HOSTEL_CHECKED_OUT", "HostelAllocation", al.getId(), Map.of("student", s.getRegistrationNo(), "damages", String.valueOf(damages),
                "charge", charge));
        return allocationView(al, s);
    }

    @Transactional(readOnly = true)
    public List<AllocationView> allocationsOf(Long windowId) {
        access.require("see hostel allocations", InventoryService.VIEWERS);
        List<HostelAllocation> list = allocations.findByWindow(windowId).stream().filter(a -> scope.covers(a.getBed().getRoom().getHostel().getId())).toList();
        Map<Long, Student> ss = students.findAllById(list.stream().map(HostelAllocation::getStudentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        return list.stream().sorted(Comparator.comparing(HostelAllocation::getAllocatedAt).reversed()).map(a -> allocationView(a, ss.get(a.getStudentId()))).toList();
    }

    // ------------------------------------------------------------------ rules

    /** Priority order (FR-HST-03): special needs, first years, students meeting the hostel fees gate, then who applied first. */
    private List<HostelApplication> ordered(HostelWindow w, List<HostelApplication> list, Map<Long, Student> ss, Map<Long, Boolean> cleared) {
        for (HostelApplication a : list) {
            Student s = ss.get(a.getStudentId());
            cleared.computeIfAbsent(s.getId(), k -> {
                var c = accounts.evaluate(s, ClearanceGate.Gate.HOSTEL, w.getAcademicYear());
                return c.enabled() && c.cleared();                       // no hostel gate: nobody is moved up for payment
            });
        }
        return list.stream().sorted(Comparator.comparingInt((HostelApplication a) -> -priority(w, a, ss.get(a.getStudentId()), cleared.get(a.getStudentId())))
                .thenComparing(HostelApplication::getSubmittedAt)).toList();
    }

    static int priority(HostelWindow w, HostelApplication a, Student s, boolean feesCleared) {
        int p = 0;
        if (w.isSpecialNeedsFirst() && a.isSpecialNeeds()) p += 4;
        if (w.isFirstYearsFirst() && s.getYearOfStudy() == 1) p += 2;
        if (w.isPaidFeesFirst() && feesCleared) p += 1;
        return p;
    }

    /** The best free bed for an applicant: right gender; preferred hostels in order; preferred room type; accessible rooms for special needs. */
    static HostelBed choose(HostelApplication a, Student s, List<HostelBed> free) {
        String g = gender(s);
        return free.stream().filter(b -> b.getRoom().getGender().name().equals(g))
                .min(Comparator.comparingInt((HostelBed b) -> preferenceRank(a, b.getRoom().getHostel().getId()))
                        .thenComparingInt(b -> a.isSpecialNeeds() && !b.getRoom().isAccessible() ? 1 : 0)
                        .thenComparingInt(b -> a.getRoomType() != null && a.getRoomType() != b.getRoom().getRoomType() ? 1 : 0))
                .orElse(null);
    }

    static int preferenceRank(HostelApplication a, Long hostelId) {
        if (hostelId.equals(a.getPreference1Id())) return 0;
        if (hostelId.equals(a.getPreference2Id())) return 1;
        if (hostelId.equals(a.getPreference3Id())) return 2;
        return 3;
    }

    static String gender(Student s) {
        return "F".equalsIgnoreCase(s.getSex()) ? "FEMALE" : "M".equalsIgnoreCase(s.getSex()) ? "MALE" : "UNKNOWN";
    }

    private String blocker(Student s) {
        if (s.getStatus() != Student.Status.ACTIVE) return "Only registered (active) students can apply for a hostel";
        if ("UNKNOWN".equals(gender(s))) return "Your sex is not recorded. Ask Registry to correct your record.";
        return null;
    }

    private boolean isOpen(HostelWindow w) {
        LocalDate today = LocalDate.now(ZONE);
        return !today.isBefore(w.getOpensOn()) && !today.isAfter(w.getClosesOn());
    }

    // ------------------------------------------------------------------ views

    private WindowView windowView(HostelWindow w) {
        List<HostelApplication> apps = applications.findByWindowIdOrderBySubmittedAtAsc(w.getId());
        List<HostelAllocation> als = allocations.findByWindow(w.getId());
        return new WindowView(w.getId(), w.getAcademicYear().getId(), w.getAcademicYear().getCode(), w.getName(), w.getOpensOn(), w.getClosesOn(),
                w.getOccupancyStarts(), w.getOccupancyEnds(), w.getPaymentDays(), w.isFirstYearsFirst(), w.isSpecialNeedsFirst(), w.isPaidFeesFirst(),
                isOpen(w), (int) apps.stream().filter(a -> a.getStatus() != HostelApplication.Status.CANCELLED).count(),
                (int) als.stream().filter(x -> x.getStatus() == HostelAllocation.Status.ALLOCATED).count(),
                (int) apps.stream().filter(a -> a.getStatus() == HostelApplication.Status.WAITLISTED).count(),
                (int) als.stream().filter(x -> x.getStatus() == HostelAllocation.Status.CHECKED_IN).count());
    }

    private ApplicationView applicationView(HostelApplication a, Student s, HostelAllocation al, boolean cleared) {
        if (al == null) al = allocations.findFirstByApplicationIdOrderByAllocatedAtDesc(a.getId()).orElse(null);
        Map<Long, String> names = hostels.findAll().stream().collect(Collectors.toMap(Hostel::getId, Hostel::getName));
        List<String> prefs = Stream.of(a.getPreference1Id(), a.getPreference2Id(), a.getPreference3Id()).filter(Objects::nonNull)
                .map(id -> names.getOrDefault(id, "—")).toList();
        return new ApplicationView(a.getId(), a.getWindow().getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getSex(), s.getProgramme().getCode(),
                s.getYearOfStudy(), prefs, a.getRoomType() == null ? null : a.getRoomType().name(), a.isSpecialNeeds(), a.getSpecialNeedsNote(),
                a.getStatus().name(), a.getSubmittedAt(), cleared, priority(a.getWindow(), a, s, cleared), al == null ? null : allocationView(al, s));
    }

    AllocationView allocationView(HostelAllocation a, Student s) {
        Invoice inv = a.getInvoiceId() == null ? null : invoices.findById(a.getInvoiceId()).orElse(null);
        ControlNumber cn = inv == null ? null : billing.currentControlNumber(inv.getId());
        Invoice dmg = a.getDamageInvoiceId() == null ? null : invoices.findById(a.getDamageInvoiceId()).orElse(null);
        HostelBed b = a.getBed();
        return new AllocationView(a.getId(), a.getApplication().getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getSex(), b.getId(),
                b.getRoom().getHostel().getName(), b.getRoom().label(), b.getLabel(), a.getStatus().name(), a.getFee(), a.getInvoiceId(),
                inv == null ? null : inv.getInvoiceNo(), inv == null ? null : inv.getStatus().name(), inv == null ? BigDecimal.ZERO : inv.balance(),
                cn == null ? null : cn.getControlNo(), a.getPayBy(), a.getMethod().name(), a.getAllocatedByName(), a.getAllocatedAt(), a.getCheckedInAt(),
                a.getCheckedInByName(), a.getCheckInCondition(), a.getCheckedOutAt(), a.getCheckedOutByName(), a.getCheckOutCondition(), a.getDamages(),
                a.getDamageCharge(), dmg == null ? null : dmg.getInvoiceNo(), a.getReleasedAt(), a.getReleaseReason(), bedChanges.refundView(a),
                bedChanges.changeCount(a.getId()));
    }

    @Transactional(readOnly = true)
    public AllocationView view(Long allocationId) {
        access.require("see hostel allocations", InventoryService.VIEWERS);
        HostelAllocation al = require(allocationId);
        scope.require(al.getBed().getRoom().getHostel().getId());
        return allocationView(al, students.findById(al.getStudentId()).orElseThrow());
    }

    private HostelAllocation require(Long id) {
        return allocations.findById(id).orElseThrow(() -> new NotFoundException("Hostel allocation", id));
    }

    private Map<Long, Student> studentsOf(List<HostelApplication> list) {
        return students.findAllById(list.stream().map(HostelApplication::getStudentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    static String tzs(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", a.longValue()) : String.format(Locale.ENGLISH, "%,.2f", a);
    }

}
