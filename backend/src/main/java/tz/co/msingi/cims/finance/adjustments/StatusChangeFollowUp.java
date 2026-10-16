package tz.co.msingi.cims.finance.adjustments;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.status.StatusChange;
import tz.co.msingi.cims.registry.status.StudentStatusAppliedEvent;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The bursary's follow-up when a status change takes effect (BP-14 with BP-17):
 * <ul>
 *   <li>postponement, discontinuation, withdrawal or death: for each of the student's invoices with something still owed (hostel fees
 *   apart: the hostel office handles beds), a waiver of the unpaid balance is raised for the bursary to approve, reduce (reject and
 *   record a smaller one) or reject, as the institution's regulations require;</li>
 *   <li>resumption: the student is billed for the academic year of the effective date if they have no fee invoice for it yet.</li>
 * </ul>
 * Nothing is posted to an invoice without the bursary's decision.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusChangeFollowUp {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Set<StatusChange.Type> STOPS = EnumSet.of(StatusChange.Type.POSTPONEMENT, StatusChange.Type.DISCONTINUATION,
            StatusChange.Type.WITHDRAWAL, StatusChange.Type.DEATH);
    private static final Set<Invoice.Status> OPEN = EnumSet.of(Invoice.Status.ISSUED, Invoice.Status.PARTIALLY_PAID);

    private final AdjustmentService adjustments;
    private final InvoiceRepository invoices;
    private final StudentRepository students;
    private final AcademicYearRepository years;
    private final AnnualBillingService annualBilling;

    @EventListener
    public void on(StudentStatusAppliedEvent e) {
        Student s = students.findById(e.studentId()).orElseThrow();
        if (STOPS.contains(e.type())) proposeWaivers(s, e);
        else if (e.type() == StatusChange.Type.RESUMPTION) billResumedYear(s, e);
    }

    private void proposeWaivers(Student s, StudentStatusAppliedEvent e) {
        String what = switch (e.type()) {
            case POSTPONEMENT -> "postponed studies";
            case DISCONTINUATION -> "was discontinued";
            case WITHDRAWAL -> "withdrew";
            default -> "is recorded as deceased";
        };
        Long by = e.decidedById();
        String byName = "CIMS, after the status change approved by " + (e.decidedByName() == null ? "the Deputy Principal" : e.decidedByName());
        for (Invoice inv : invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.STUDENT, s.getId())) {
            if (!OPEN.contains(inv.getStatus()) || inv.getPurpose() != null && inv.getPurpose().startsWith("HOSTEL")) continue;
            BigDecimal room = adjustments.waivableOn(inv);
            if (room.signum() <= 0 || by == null) continue;
            adjustments.raise(FinanceAdjustment.Kind.WAIVER, s, inv, room,
                    s.getRegistrationNo() + " " + what + " with effect from " + DAY.format(e.effectiveDate()) + ". Proposed: waive the unpaid TZS "
                            + room.toPlainString() + " on " + inv.getInvoiceNo() + ". Approve it, or reject it and record a smaller waiver if the "
                            + "regulations keep part of the fee due.",
                    e.reference(), FinanceAdjustment.Origin.STATUS_CHANGE, e.changeId(), by, byName);
        }
    }

    private void billResumedYear(Student s, StudentStatusAppliedEvent e) {
        Optional<AcademicYear> year = years.findAllByOrderByStartDateDesc().stream()
                .filter(y -> !e.effectiveDate().isBefore(y.getStartDate()) && !e.effectiveDate().isAfter(y.getEndDate())).findFirst();
        year.ifPresent(y -> annualBilling.billStudent(s, y)
                .ifPresentOrElse(inv -> log.info("{} resumed: fees for {} on {}", s.getRegistrationNo(), y.getCode(), inv.getInvoiceNo()),
                        () -> log.warn("{} resumed but no approved fee structure covers them for {}", s.getRegistrationNo(), y.getCode())));
    }
}
