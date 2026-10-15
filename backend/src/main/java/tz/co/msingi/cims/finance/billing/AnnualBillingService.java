package tz.co.msingi.cims.finance.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.fees.FeeRepositories.FeeStructureRepository;
import tz.co.msingi.cims.finance.fees.FeeStructure;
import tz.co.msingi.cims.finance.fees.FeeStructureLine;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Annual fee invoices (FR-FIN-02/03): raised from the approved fee structure when an offer is accepted
 * (or in a billing run for anyone missed), with a GePG control number sent by SMS. One annual invoice per payer per
 * academic year. At hand-over the invoice moves with its payments from the applicant to the new student record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnualBillingService {

    public static final String PURPOSE = "ANNUAL_FEES";
    static final Set<Invoice.Status> VOID = EnumSet.of(Invoice.Status.CANCELLED, Invoice.Status.CREDITED);

    private final BillingService billing;
    private final InvoiceRepository invoices;
    private final FeeStructureRepository structures;
    private final ApplicationRepository applications;
    private final StudentRepository students;
    private final AcademicYearRepository years;
    private final AuditService audit;

    public record RunResult(int billed, int alreadyBilled, int noStructure, List<String> programmesWithoutStructure) {
    }

    /** Bills an accepted applicant for year 1 (after acceptance has committed). Empty if no approved structure exists yet. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Invoice> billAcceptedApplicationInNewTransaction(Long applicationId) {
        Application app = applications.findById(applicationId).orElseThrow(() -> new NotFoundException("Application", applicationId));
        return billApplication(app);
    }

    @Transactional
    public Optional<Invoice> billApplication(Application app) {
        if (app.getStatus() != Application.Status.ACCEPTED) return Optional.empty();
        Optional<Student> student = students.findByApplicationId(app.getId());
        AcademicYear year = app.getWindow().getAcademicYear();
        if (student.isPresent()) return billStudent(student.get(), year);
        Applicant ap = app.getApplicant();
        Optional<Invoice> existing = annual(Invoice.PayerType.APPLICANT, ap.getId(), year.getId());
        if (existing.isPresent()) return existing;
        Programme programme = app.getSelectedWindowProgramme().getProgramme();
        var joining = app.getWindow().getJoiningInstructions();
        java.time.LocalDate due = joining != null ? joining.reportingDate() : year.getStartDate();
        return approved(year, programme, (short) 1, app.getWindow().getIntake() == null ? null : app.getWindow().getIntake().getId(), null)
                .map(f -> issue(f, Invoice.PayerType.APPLICANT, ap.getId(), ap.fullName(),
                ap.getPhone(), ap.getEmail(), isForeign(ap.getNationality()), app.getId(), due));
    }

    /** Bills a student for an academic year at their year of study. Takes over an invoice raised before hand-over. */
    @Transactional
    public Optional<Invoice> billStudent(Student s, AcademicYear year) {
        Optional<Invoice> existing = annual(Invoice.PayerType.STUDENT, s.getId(), year.getId());
        if (existing.isPresent()) return existing;
        if (s.getApplicantId() != null) {
            Optional<Invoice> fromApplicant = annual(Invoice.PayerType.APPLICANT, s.getApplicantId(), year.getId());
            if (fromApplicant.isPresent()) {
                moveToStudent(fromApplicant.get(), s);
                return fromApplicant;
            }
        }
        java.time.LocalDate due = s.getApplicationId() == null ? year.getStartDate() : applications.findById(s.getApplicationId())
                .map(a -> a.getWindow().getJoiningInstructions()).map(j -> j.reportingDate()).orElse(year.getStartDate());
        return approved(year, s.getProgramme(), s.getYearOfStudy(), s.getIntake() == null ? null : s.getIntake().getId(), s.getStudentCategoryId())
                .map(f -> issue(f, Invoice.PayerType.STUDENT, s.getId(),
                s.fullName(), s.getPhone(), s.getEmail(), isForeign(s.getNationality()), s.getApplicationId(), due));
    }

    /** At hand-over: annual invoices raised to the applicant now belong to the student, payments included. */
    @Transactional
    public void transferToStudent(Student s) {
        if (s.getApplicantId() == null) return;
        invoices.findByPayerTypeAndPayerIdAndPurposeAndStatusNotIn(Invoice.PayerType.APPLICANT, s.getApplicantId(), PURPOSE, VOID)
                .forEach(inv -> moveToStudent(inv, s));
    }

    /**
     * Billing run for an academic year (FR-FIN-02, bulk): new students of the year and accepted applicants not yet
     * handed over who have no annual invoice. Duplicate-safe: running it twice bills no one twice.
     */
    @Transactional
    public RunResult run(Long academicYearId) {
        AcademicYear year = years.findById(academicYearId).orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        int billed = 0, already = 0, missing = 0;
        Set<String> without = new TreeSet<>();
        for (Student s : students.findByEntryAcademicYearIdAndStatusIn(academicYearId, EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE))) {
            boolean had = annual(Invoice.PayerType.STUDENT, s.getId(), academicYearId).isPresent()
                    || (s.getApplicantId() != null && annual(Invoice.PayerType.APPLICANT, s.getApplicantId(), academicYearId).isPresent());
            Optional<Invoice> inv = billStudent(s, year);
            if (had) already++;
            else if (inv.isPresent()) billed++;
            else { missing++; without.add(s.getProgramme().getName() + ", year " + s.getYearOfStudy()); }
        }
        for (Application a : applications.findByWindowAcademicYearIdAndStatus(academicYearId, Application.Status.ACCEPTED)) {
            if (students.findByApplicationId(a.getId()).isPresent()) continue;
            boolean had = annual(Invoice.PayerType.APPLICANT, a.getApplicant().getId(), academicYearId).isPresent();
            Optional<Invoice> inv = billApplication(a);
            if (had) already++;
            else if (inv.isPresent()) billed++;
            else { missing++; without.add(a.getSelectedWindowProgramme().getProgramme().getName() + ", year 1"); }
        }
        audit.record("BILLING_RUN", "AcademicYear", academicYearId, Map.of("billed", billed, "alreadyBilled", already, "noStructure", missing));
        return new RunResult(billed, already, missing, List.copyOf(without));
    }

    // ------------------------------------------------------------------ helpers

    private Invoice issue(FeeStructure f, Invoice.PayerType payerType, Long payerId, String name, String phone, String email,
                          boolean foreign, Long referenceId, java.time.LocalDate dueDate) {
        List<BillingService.NewInvoice.Line> lines = f.getLines().stream()
                .sorted(Comparator.comparing((FeeStructureLine l) -> l.getInstalment()).thenComparing(l -> l.getFeeItem().getAllocationPriority()))
                .filter(l -> l.amountFor(foreign).signum() > 0)
                .map(l -> new BillingService.NewInvoice.Line(l.getFeeItem().getCode(), l.getFeeItem().getName(), l.amountFor(foreign),
                        l.getFeeItem().getId(), l.getFeeItem().getAllocationPriority(), l.getInstalment()))
                .toList();
        String description = "Fees " + f.getAcademicYear().getCode() + " — " + f.getProgramme().getCode() + " year " + f.getYearOfStudy();
        Invoice inv = billing.createInvoice(new BillingService.NewInvoice(payerType, payerId, name, phone, email, PURPOSE, referenceId,
                description, lines, f.getAcademicYear().getId(), f.getId()));
        inv.setDueDate(dueDate); // fees are due by reporting (first instalment checked at registration)
        audit.record("ANNUAL_INVOICE_ISSUED", "Invoice", inv.getId(), Map.of("invoiceNo", inv.getInvoiceNo(),
                "structure", f.getId(), "version", f.getStructureVersion(), "total", inv.getTotalAmount(), "foreign", foreign));
        billing.ensureControlNumber(inv.getId()); // control number by SMS (BP-06 step 3)
        return inv;
    }

    private void moveToStudent(Invoice inv, Student s) {
        inv.setPayerType(Invoice.PayerType.STUDENT);
        inv.setPayerId(s.getId());
        inv.setPayerName(s.fullName());
        audit.record("INVOICE_TRANSFERRED_TO_STUDENT", "Invoice", inv.getId(),
                Map.of("invoiceNo", inv.getInvoiceNo(), "registrationNo", s.getRegistrationNo()));
    }

    private Optional<Invoice> annual(Invoice.PayerType type, Long payerId, Long academicYearId) {
        return invoices.findByPayerTypeAndPayerIdAndPurposeAndStatusNotIn(type, payerId, PURPOSE, VOID).stream()
                .filter(i -> academicYearId.equals(i.getAcademicYearId())).findFirst();
    }

    /** The approved structure for the payer's intake and category, falling back to the general one (BR-22). */
    private Optional<FeeStructure> approved(AcademicYear year, Programme programme, short yearOfStudy, Long intakeId, Long categoryId) {
        return tz.co.msingi.cims.finance.fees.FeeService.best(structures.findAllByAcademicYearIdAndProgrammeIdAndYearOfStudyAndStatus(
                year.getId(), programme.getId(), yearOfStudy, FeeStructure.Status.APPROVED), intakeId, categoryId);
    }

    /** Foreign rates apply to non-Tanzanians (an applicant without a stated nationality is charged the local rate). */
    static boolean isForeign(String nationality) {
        if (nationality == null || nationality.isBlank()) return false;
        String n = nationality.trim().toLowerCase(Locale.ROOT);
        return !(n.startsWith("tanzania") || n.equals("tz") || n.equals("mtanzania"));
    }
}
