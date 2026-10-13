package tz.co.msingi.cims.finance.accounts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.service.ApplicantProfileService;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountDtos.InvoiceView;
import tz.co.msingi.cims.finance.accounts.AccountDtos.LineView;
import tz.co.msingi.cims.finance.accounts.AccountDtos.PaymentView;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.InvoiceLine;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.finance.fees.FeeRepositories.ClearanceGateRepository;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Statements of account (FR-FIN-12) and clearance evaluation against the configured gates (FR-FIN-06). */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final StudentRepository students;
    private final ApplicantProfileService profiles;
    private final ClearanceGateRepository gates;
    private final BillingService billing;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    // ------------------------------------------------------------------ statements

    /** A student's account: their invoices plus the invoices they had as an applicant (e.g. the application fee). */
    @Transactional(readOnly = true)
    public Statement forStudent(Long studentId) {
        Student s = students.findById(studentId).orElseThrow(() -> new NotFoundException("Student", studentId));
        return statement(s.fullName(), s.getRegistrationNo(), s.getId(), s.getApplicantId());
    }

    /** The signed-in applicant's own account (and their student account once handed over). */
    @Transactional(readOnly = true)
    public Statement mine() {
        Applicant a = profiles.currentApplicant();
        Student s = students.findByApplicantId(a.getId()).orElse(null);
        return statement(a.fullName(), s == null ? null : s.getRegistrationNo(), s == null ? null : s.getId(), a.getId());
    }

    /** Control number for one of the signed-in applicant's (or student's) own invoices. */
    @Transactional
    public Statement requestMyControlNumber(Long invoiceId) {
        requireMine(invoiceId);
        billing.ensureControlNumber(invoiceId);
        return mine();
    }

    /** Throws unless the invoice belongs to the signed-in applicant or to their student record. */
    @Transactional(readOnly = true)
    public Invoice requireMine(Long invoiceId) {
        Applicant a = profiles.currentApplicant();
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        boolean mine = (inv.getPayerType() == Invoice.PayerType.APPLICANT && inv.getPayerId().equals(a.getId()))
                || (inv.getPayerType() == Invoice.PayerType.STUDENT
                && students.findById(inv.getPayerId()).map(st -> a.getId().equals(st.getApplicantId())).orElse(false));
        if (!mine) throw new NotFoundException("Invoice", invoiceId);
        return inv;
    }

    private Statement statement(String name, String regNo, Long studentId, Long applicantId) {
        List<Invoice> list = new ArrayList<>();
        if (studentId != null) list.addAll(invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.STUDENT, studentId));
        if (applicantId != null) list.addAll(invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.APPLICANT, applicantId));
        list.sort(Comparator.comparing(Invoice::getCreatedAt).reversed());
        List<Invoice> live = list.stream().filter(i -> i.getStatus() != Invoice.Status.CANCELLED && i.getStatus() != Invoice.Status.CREDITED).toList();
        Map<Long, Invoice> byId = list.stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        List<Payment> pays = list.isEmpty() ? List.of() : payments.findByInvoiceIdInOrderByPaidAtAsc(byId.keySet());
        BigDecimal billed = live.stream().map(Invoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = live.stream().map(Invoice::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = pays.stream().map(Payment::getUnallocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sponsorPending = live.stream().filter(i -> i.getPayerType() == Invoice.PayerType.STUDENT)
                .map(i -> invoices.sponsorPending(i.getId())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AccountDtos.SponsorShare> sponsors = studentId == null ? List.of() : jdbc.query("""
                select s.name, y.code, x.coverage_percent, x.cap_amount, x.reference from sponsorships x join sponsors s on s.id = x.sponsor_id
                join academic_years y on y.id = x.academic_year_id where x.student_id = ? and x.status = 'ACTIVE' order by y.code desc""",
                (rs, i) -> new AccountDtos.SponsorShare(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5)), studentId);
        return new Statement(name, regNo, billed, paid, billed.subtract(paid), credit,
                list.stream().map(this::invoiceView).toList(),
                pays.stream().map(p -> new PaymentView(p.getReceiptNo(), p.getPaidAt(), p.getAmount(), p.getUnallocatedAmount(),
                        p.getChannel(), p.getPspName(), byId.get(p.getInvoice().getId()).getInvoiceNo(), p.getControlNo())).toList(),
                sponsorPending, sponsors);
    }

    private InvoiceView invoiceView(Invoice i) {
        ControlNumber cn = billing.currentControlNumber(i.getId());
        return new InvoiceView(i.getId(), i.getInvoiceNo(), i.getPurpose(), i.getDescription(), i.getPayerType().name(), i.getTotalAmount(),
                i.getPaidAmount(), i.balance(), i.getStatus().name(), i.getDueDate(), i.getCreatedAt(),
                cn == null ? null : cn.getControlNo(), cn == null ? null : cn.getExpiresAt(),
                i.getLines().stream().map(l -> new LineView(l.getFeeItemCode(), l.getDescription(), l.getAmount(), l.getPaidAmount(),
                        l.balance(), l.getInstalment())).toList());
    }

    // ------------------------------------------------------------------ clearance (FR-FIN-06)

    @Transactional(readOnly = true)
    public Clearance clearance(Long studentId, ClearanceGate.Gate gate) {
        Student s = students.findById(studentId).orElseThrow(() -> new NotFoundException("Student", studentId));
        return evaluate(s, gate);
    }

    /**
     * Whether the student meets the gate for their current academic year (for a new student, the entry year):
     * at least the configured share of the annual fees paid and, if required, the whole first instalment.
     */
    @Transactional(readOnly = true)
    public Clearance evaluate(Student s, ClearanceGate.Gate gate) {
        return evaluate(s, gate, s.getEntryAcademicYear());
    }

    /** The gate for a given academic year (e.g. a continuing student registering for a semester of their second year). */
    @Transactional(readOnly = true)
    public Clearance evaluate(Student s, ClearanceGate.Gate gate, tz.co.msingi.cims.academic.domain.AcademicYear year) {
        ClearanceGate g = gates.findByGate(gate).orElse(null);
        Invoice inv = invoices.findByPayerTypeAndPayerIdAndPurposeAndStatusNotIn(Invoice.PayerType.STUDENT, s.getId(),
                        AnnualBillingService.PURPOSE, java.util.EnumSet.of(Invoice.Status.CANCELLED, Invoice.Status.CREDITED)).stream()
                .filter(i -> year.getId().equals(i.getAcademicYearId())).findFirst().orElse(null);
        if (g == null || !g.isEnabled() || (g.getMinPercentPaid().signum() == 0 && !g.isFirstInstalmentRequired())) {
            return result(gate, false, true, year.getCode(), inv, BigDecimal.ZERO, false, null);
        }
        if (inv == null) {
            return new Clearance(gate.name(), true, false, year.getCode(), null, null, null, g.getMinPercentPaid(), null,
                    g.isFirstInstalmentRequired(), null, null, null, null,
                    "No fees invoice for " + year.getCode() + " yet. Ask the Bursar's office to run billing.");
        }
        BigDecimal required = inv.getTotalAmount().multiply(g.getMinPercentPaid()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal firstBalance = inv.getLines().stream().filter(l -> l.getInstalment() == 1).map(InvoiceLine::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // what sponsors have been invoiced for this student and not yet paid counts as paid for clearance (BP-17)
        BigDecimal sponsor = invoices.sponsorPending(inv.getId());
        BigDecimal needed = required.subtract(inv.getPaidAmount()).subtract(sponsor).max(BigDecimal.ZERO);
        if (g.isFirstInstalmentRequired()) needed = needed.max(firstBalance.subtract(sponsor).max(BigDecimal.ZERO));
        boolean cleared = needed.signum() == 0;
        String reason = cleared ? null : "TZS " + tzs(needed) + " still to pay for " + gate.name().toLowerCase()
                + " clearance (" + describe(g) + ")";
        return result(gate, true, cleared, year.getCode(), inv, g.getMinPercentPaid(), g.isFirstInstalmentRequired(), reason, firstBalance, needed);
    }

    private Clearance result(ClearanceGate.Gate gate, boolean enabled, boolean cleared, String year, Invoice inv, BigDecimal min,
                             boolean first, String reason) {
        return result(gate, enabled, cleared, year, inv, min, first, reason, null, BigDecimal.ZERO);
    }

    private Clearance result(ClearanceGate.Gate gate, boolean enabled, boolean cleared, String year, Invoice inv, BigDecimal min,
                             boolean first, String reason, BigDecimal firstBalance, BigDecimal needed) {
        BigDecimal pct = inv == null || inv.getTotalAmount().signum() == 0 ? null
                : inv.getPaidAmount().multiply(BigDecimal.valueOf(100)).divide(inv.getTotalAmount(), 1, RoundingMode.HALF_UP);
        return new Clearance(gate.name(), enabled, cleared, year, inv == null ? null : inv.getTotalAmount(),
                inv == null ? null : inv.getPaidAmount(), inv == null ? null : inv.balance(), min, pct, first, firstBalance, needed,
                inv == null ? null : inv.getId(), inv == null ? null : inv.getInvoiceNo(), reason);
    }

    /** e.g. 650000.00 → 650,000 and 1250.50 → 1,250.50 */
    static String tzs(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(java.util.Locale.ENGLISH, "%,d", a.longValue())
                : String.format(java.util.Locale.ENGLISH, "%,.2f", a);
    }

    private static String describe(ClearanceGate g) {
        List<String> parts = new ArrayList<>();
        if (g.getMinPercentPaid().signum() > 0) parts.add(g.getMinPercentPaid().stripTrailingZeros().toPlainString() + "% of annual fees");
        if (g.isFirstInstalmentRequired()) parts.add("the first instalment");
        return String.join(" and ", parts) + " required";
    }

    /** Throws FINANCIAL_CLEARANCE_REQUIRED when the student does not meet the gate. */
    public void requireCleared(Student s, ClearanceGate.Gate gate) {
        Clearance c = evaluate(s, gate);
        if (!c.cleared()) {
            throw new BusinessRuleException("FINANCIAL_CLEARANCE_REQUIRED", c.reason());
        }
    }

}
