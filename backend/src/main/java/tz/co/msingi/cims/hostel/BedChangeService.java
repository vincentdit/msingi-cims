package tz.co.msingi.cims.hostel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.finance.adjustments.AdjustmentRepository;
import tz.co.msingi.cims.finance.adjustments.AdjustmentService;
import tz.co.msingi.cims.finance.adjustments.FinanceAdjustment;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.hostel.HostelDtos.BedChangeView;
import tz.co.msingi.cims.hostel.HostelDtos.RefundView;
import tz.co.msingi.cims.hostel.HostelRepositories.AllocationRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.BedRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.WindowRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.status.StatusChange;
import tz.co.msingi.cims.registry.status.StudentStatusAppliedEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bed changes and refunds (BP-12, with BP-17 for the money):
 * <ul>
 *   <li>the hostel office moves a student to another free bed of the right gender; when the new room's fee category costs more or less,
 *   the difference for the rest of the occupancy period is charged on a new invoice, or waived (if still unpaid) or refunded (if paid)
 *   once the bursary approves;</li>
 *   <li>a student who checks out early, or whose paid allocation is cancelled, can be refunded the unused part — raised here, decided by
 *   the bursary;</li>
 *   <li>when a student's studies stop (postponement, suspension, discontinuation, withdrawal, death), an allocation still waiting for
 *   payment is released; a paid or checked-in one is left for the warden to check out.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BedChangeService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    public static final String CHANGE_PURPOSE = "HOSTEL_CHANGE";
    private static final Set<StatusChange.Type> STOPS = EnumSet.of(StatusChange.Type.POSTPONEMENT, StatusChange.Type.SUSPENSION,
            StatusChange.Type.DISCONTINUATION, StatusChange.Type.WITHDRAWAL, StatusChange.Type.DEATH);

    private final AllocationRepository allocations;
    private final BedRepository beds;
    private final WindowRepository windows;
    private final StudentRepository students;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final AdjustmentService adjustments;
    private final AdjustmentRepository adjustmentRecords;
    private final InventoryService inventory;
    private final ExamAccess access;
    private final HostelScope scope;
    private final Messaging messaging;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ moving a student to another bed

    @Transactional
    public HostelAllocation move(Long allocationId, Long bedId, String reason) {
        access.require("move students to another bed", InventoryService.WARDENS);
        HostelAllocation al = allocations.findById(allocationId).orElseThrow(() -> new NotFoundException("Hostel allocation", allocationId));
        scope.require(al.getBed().getRoom().getHostel().getId());
        if (!al.active()) throw new BusinessRuleException("NOT_ACTIVE", "Only a current allocation (allocated or checked in) can move to another bed");
        HostelBed from = al.getBed();
        HostelBed to = beds.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        scope.require(to.getRoom().getHostel().getId());
        if (from.getId().equals(to.getId())) throw new BusinessRuleException("SAME_BED", "The student is already in that bed");
        Student s = students.findById(al.getStudentId()).orElseThrow();
        if (!to.getRoom().getGender().name().equals(AllocationService.gender(s))) {
            throw new BusinessRuleException("GENDER_MISMATCH", to.getRoom().label() + " is designated for " + to.getRoom().getGender().name().toLowerCase() + " students");
        }
        if (!InventoryService.usable(to)) throw new BusinessRuleException("BED_UNAVAILABLE", "That bed is out of service");
        if (inventory.activeByBed().containsKey(to.getId())) throw new BusinessRuleException("BED_TAKEN", "That bed is already allocated");

        HostelWindow w = windows.findById(al.getWindowId()).orElseThrow();
        BigDecimal oldFee = from.getRoom().getFeeCategory().getAmount();
        BigDecimal newFee = to.getRoom().getFeeCategory().getAmount();
        BigDecimal diff = newFee.subtract(oldFee).multiply(remainingShare(w, today())).setScale(0, RoundingMode.HALF_UP);
        al.setBed(to);
        allocations.saveAndFlush(al);

        Long invoiceId = null;
        FinanceAdjustment adj = null;
        String where = place(to);
        if (diff.signum() > 0) {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(),
                    CHANGE_PURPOSE, al.getId(), "Hostel bed change " + w.getAcademicYear().getCode() + ": " + where,
                    List.of(new BillingService.NewInvoice.Line("HOSTEL", "Difference for the rest of the period (" + to.getRoom().getFeeCategory().getName() + ")", diff)),
                    w.getAcademicYear().getId(), null));
            invoiceId = inv.getId();
            billing.ensureControlNumber(inv.getId());
        } else if (diff.signum() < 0 && al.getInvoiceId() != null) {
            adj = creditOrRefund(al, s, diff.negate(), "Moved from " + place(from) + " to " + where + " (" + reason.trim() + "): the lower fee for the rest of the period",
                    FinanceAdjustment.Origin.HOSTEL_CHANGE);
        }
        al.setFee(al.getFee().add(diff).max(BigDecimal.ZERO));
        jdbc.update("""
                insert into hostel_bed_changes (tenant_id, allocation_id, from_bed_id, to_bed_id, old_fee, new_fee, difference, invoice_id, adjustment_id,
                                                reason, changed_by_name) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                al.getId(), from.getId(), to.getId(), oldFee, newFee, diff, invoiceId, adj == null ? null : adj.getId(), reason.trim(), CurrentUser.name());
        audit.record("HOSTEL_BED_CHANGED", "HostelAllocation", al.getId(), Map.of("student", s.getRegistrationNo(), "from", place(from), "to", where,
                "difference", diff, "reason", reason.trim()));
        messaging.event("HOSTEL_BED_CHANGED", s.getPhone(), Map.of("place", where));
        return al;
    }

    @Transactional(readOnly = true)
    public List<BedChangeView> changes(Long allocationId) {
        access.require("see hostel allocations", InventoryService.VIEWERS);
        HostelAllocation al = allocations.findById(allocationId).orElseThrow(() -> new NotFoundException("Hostel allocation", allocationId));
        scope.require(al.getBed().getRoom().getHostel().getId());
        return jdbc.query("""
                select c.id, fh.name || ', ' || case when coalesce(fr.block, '') = '' then '' else fr.block || ' · ' end || 'Room ' || fr.room_no || ', bed ' || fb.label,
                       th.name || ', ' || case when coalesce(tr.block, '') = '' then '' else tr.block || ' · ' end || 'Room ' || tr.room_no || ', bed ' || tb.label,
                       c.old_fee, c.new_fee, c.difference, i.invoice_no, a.adjustment_no, a.kind, a.status, c.reason, c.changed_by_name, c.changed_at
                from hostel_bed_changes c
                join hostel_beds fb on fb.id = c.from_bed_id join hostel_rooms fr on fr.id = fb.room_id join hostels fh on fh.id = fr.hostel_id
                join hostel_beds tb on tb.id = c.to_bed_id join hostel_rooms tr on tr.id = tb.room_id join hostels th on th.id = tr.hostel_id
                left join invoices i on i.id = c.invoice_id left join finance_adjustments a on a.id = c.adjustment_id
                where c.allocation_id = ? order by c.changed_at desc, c.id desc""", (rs, n) -> new BedChangeView(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getObject(13, java.time.OffsetDateTime.class)), allocationId);
    }

    int changeCount(Long allocationId) {
        Integer n = jdbc.queryForObject("select count(*) from hostel_bed_changes where allocation_id = ?", Integer.class, allocationId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ refunds when a stay ends early or is cancelled after paying

    /**
     * Raises a refund of the unused part of what was paid on the allocation's hostel invoice: the share of the occupancy period left
     * after {@code from} (all of it before the period starts). Returns null when nothing was paid or nothing is left.
     */
    FinanceAdjustment refundUnused(HostelAllocation al, LocalDate from, String reason, FinanceAdjustment.Origin origin) {
        if (al.getInvoiceId() == null) return null;
        Invoice inv = invoices.findById(al.getInvoiceId()).orElseThrow();
        HostelWindow w = windows.findById(al.getWindowId()).orElseThrow();
        if (w.getOccupancyEnds() == null) {
            throw new BusinessRuleException("OCCUPANCY_DATES_REQUIRED", "Set the occupancy period of " + w.getName()
                    + " (Hostels › Windows) so CIMS can work out the unused part, or record the refund in Finance › Waivers & refunds");
        }
        BigDecimal amount = adjustments.refundableFrom(inv).multiply(remainingShare(w, from)).setScale(0, RoundingMode.DOWN);
        if (amount.signum() <= 0) return null;
        Student s = students.findById(al.getStudentId()).orElseThrow();
        FinanceAdjustment a = adjustments.raise(FinanceAdjustment.Kind.REFUND, s, inv, amount, reason, "Hostel allocation " + al.getId() + ", " + place(al.getBed()),
                origin, al.getId(), CurrentUser.requireId(), CurrentUser.name());
        al.setRefundAdjustmentId(a.getId());
        return a;
    }

    /**
     * A paid allocation cancelled before check-in: the unpaid rest of the hostel invoice is proposed for waiver and everything paid for
     * refund, both for the bursary to decide.
     */
    void settleCancelled(HostelAllocation al, String reason) {
        Invoice inv = invoices.findById(al.getInvoiceId()).orElseThrow();
        Student s = students.findById(al.getStudentId()).orElseThrow();
        String evidence = "Hostel allocation " + al.getId() + ", " + place(al.getBed());
        BigDecimal waive = adjustments.waivableOn(inv);
        if (waive.signum() > 0) {
            adjustments.raise(FinanceAdjustment.Kind.WAIVER, s, inv, waive, reason, evidence, FinanceAdjustment.Origin.HOSTEL_CANCEL, al.getId(),
                    CurrentUser.requireId(), CurrentUser.name());
        }
        BigDecimal refund = adjustments.refundableFrom(inv);
        if (refund.signum() > 0) {
            FinanceAdjustment a = adjustments.raise(FinanceAdjustment.Kind.REFUND, s, inv, refund, reason, evidence, FinanceAdjustment.Origin.HOSTEL_CANCEL,
                    al.getId(), CurrentUser.requireId(), CurrentUser.name());
            al.setRefundAdjustmentId(a.getId());
        }
    }

    RefundView refundView(HostelAllocation al) {
        if (al.getRefundAdjustmentId() == null) return null;
        return adjustmentRecords.findById(al.getRefundAdjustmentId())
                .map(a -> new RefundView(a.getAdjustmentNo(), a.getAmount(), a.getStatus().name())).orElse(null);
    }

    /** For a lower fee after a move: a waiver of what is still unpaid on the hostel invoice first, then a refund of what was paid. */
    private FinanceAdjustment creditOrRefund(HostelAllocation al, Student s, BigDecimal amount, String reason, FinanceAdjustment.Origin origin) {
        Invoice inv = invoices.findById(al.getInvoiceId()).orElseThrow();
        String evidence = "Hostel allocation " + al.getId();
        BigDecimal waivable = inv.getStatus() == Invoice.Status.CANCELLED ? BigDecimal.ZERO : adjustments.waivableOn(inv);
        FinanceAdjustment last = null;
        BigDecimal waive = amount.min(waivable);
        if (waive.signum() > 0) {
            last = adjustments.raise(FinanceAdjustment.Kind.WAIVER, s, inv, waive, reason, evidence, origin, al.getId(), CurrentUser.requireId(), CurrentUser.name());
        }
        BigDecimal refund = amount.subtract(waive).min(adjustments.refundableFrom(inv));
        if (refund.signum() > 0) {
            last = adjustments.raise(FinanceAdjustment.Kind.REFUND, s, inv, refund, reason, evidence, origin, al.getId(), CurrentUser.requireId(), CurrentUser.name());
        }
        return last;
    }

    // ------------------------------------------------------------------ status changes

    @EventListener
    public void on(StudentStatusAppliedEvent e) {
        if (!STOPS.contains(e.type())) return;
        for (HostelAllocation al : allocations.findByStudent(e.studentId())) {
            if (al.getStatus() != HostelAllocation.Status.ALLOCATED) continue;
            Invoice inv = al.getInvoiceId() == null ? null : invoices.findById(al.getInvoiceId()).orElse(null);
            if (inv != null && inv.getPaidAmount().signum() > 0) continue;       // paid: the warden decides with the student
            al.setStatus(HostelAllocation.Status.RELEASED);
            al.setReleasedAt(java.time.OffsetDateTime.now());
            al.setReleaseReason("Student status changed: " + e.type().verb);
            al.getApplication().setStatus(HostelApplication.Status.RELEASED);
            if (inv != null) billing.cancelIfUnpaid(inv.getId(), "Hostel allocation released after a status change");
            audit.record("HOSTEL_ALLOCATION_RELEASED", "HostelAllocation", al.getId(), Map.of("reason", al.getReleaseReason()));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The share of the occupancy period left from the given day: 1 before it starts (or with no dates), 0 after it ends. */
    static BigDecimal remainingShare(HostelWindow w, LocalDate from) {
        if (w.getOccupancyStarts() == null || w.getOccupancyEnds() == null) return BigDecimal.ONE;
        long total = ChronoUnit.DAYS.between(w.getOccupancyStarts(), w.getOccupancyEnds()) + 1;
        if (total <= 0 || !from.isAfter(w.getOccupancyStarts())) return BigDecimal.ONE;
        long left = ChronoUnit.DAYS.between(from, w.getOccupancyEnds()) + 1;
        if (left <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(left).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static String place(HostelBed b) {
        return b.getRoom().getHostel().getName() + ", " + b.getRoom().label() + ", bed " + b.getLabel();
    }
}
