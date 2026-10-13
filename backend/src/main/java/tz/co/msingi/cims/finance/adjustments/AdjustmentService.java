package tz.co.msingi.cims.finance.adjustments;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos.AdjustmentRequest;
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos.AdjustmentView;
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos.InvoiceOption;
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos.SettingsView;
import tz.co.msingi.cims.finance.adjustments.AdjustmentDtos.StudentOptions;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Waivers and refunds (BP-17 steps 3-4, FR-FIN-08/10). The Accountant records the request with its evidence; policy checks keep a
 * waiver within what the student still owes on the invoice and a refund within the student's credit. The Bursar approves; above the
 * institution's threshold the Principal approves as well (two levels), and nobody approves their own request or both levels. An
 * approved waiver becomes a credit note posted to the invoice; an approved refund becomes a refund voucher that uses up the credit, and
 * the Accountant records when it is paid out. The student is told by SMS; everything is in the audit trail.
 */
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("500000");
    private static final Set<Invoice.Status> VOID = EnumSet.of(Invoice.Status.CANCELLED, Invoice.Status.CREDITED);
    private static final Set<FinanceAdjustment.Status> OPEN = EnumSet.of(FinanceAdjustment.Status.PENDING, FinanceAdjustment.Status.ENDORSED);

    private final AdjustmentRepository adjustments;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final StudentRepository students;
    private final BillingService billing;
    private final NumberSequenceService sequences;
    private final Messaging messaging;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public SettingsView settings() {
        return new SettingsView(threshold());
    }

    @Transactional
    public SettingsView updateSettings(BigDecimal threshold) {
        if (!has(Role.INSTITUTION_ADMIN, Role.BURSAR)) throw new AccessDeniedException("Only the Bursar sets the approval threshold");
        if (threshold == null || threshold.signum() < 0) throw new BusinessRuleException("INVALID_SETTING", "The threshold cannot be negative");
        int n = jdbc.update("update finance_settings set approval_threshold = ?, updated_at = now()", threshold);
        if (n == 0) jdbc.update("insert into finance_settings (tenant_id, approval_threshold) values (cims_current_tenant(), ?)", threshold);
        audit.record("FINANCE_SETTINGS_UPDATED", "FinanceSettings", null, Map.of("approvalThreshold", threshold));
        return settings();
    }

    BigDecimal threshold() {
        return jdbc.queryForList("select approval_threshold from finance_settings", BigDecimal.class).stream().findFirst().orElse(DEFAULT_THRESHOLD);
    }

    // ------------------------------------------------------------------ requests (BP-17 step 3)

    /** The student's open invoices with what can still be waived, and the credit that can be refunded. */
    @Transactional(readOnly = true)
    public StudentOptions options(Long studentId, String registrationNo) {
        Student s = student(studentId, registrationNo);
        List<InvoiceOption> open = invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.STUDENT, s.getId()).stream()
                .filter(i -> !VOID.contains(i.getStatus()) && i.balance().signum() > 0)
                .map(i -> new InvoiceOption(i.getId(), i.getInvoiceNo(), i.getDescription(), i.balance(), waivable(i))).toList();
        BigDecimal credit = credit(s.getId());
        return new StudentOptions(s.getId(), s.getRegistrationNo(), s.fullName(), credit, refundable(s.getId(), credit), open);
    }

    @Transactional
    public AdjustmentView request(AdjustmentRequest req) {
        if (!has(Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT)) throw new AccessDeniedException("Only the bursary records waivers and refunds");
        Student s = student(req.studentId(), req.registrationNo());
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        Invoice inv = null;
        if (req.kind() == FinanceAdjustment.Kind.WAIVER) {
            if (req.invoiceId() == null) throw new BusinessRuleException("INVOICE_REQUIRED", "Choose the invoice to waive");
            inv = invoices.findById(req.invoiceId()).orElseThrow(() -> new NotFoundException("Invoice", req.invoiceId()));
            if (inv.getPayerType() != Invoice.PayerType.STUDENT || !inv.getPayerId().equals(s.getId())) {
                throw new BusinessRuleException("NOT_STUDENTS_INVOICE", "That invoice is not " + s.getRegistrationNo() + "'s");
            }
            if (VOID.contains(inv.getStatus())) throw new BusinessRuleException("INVOICE_VOID", "That invoice is cancelled");
            BigDecimal room = waivable(inv);
            if (amount.compareTo(room) > 0) {
                throw new BusinessRuleException("WAIVER_TOO_LARGE", "At most TZS " + tzs(room) + " of " + inv.getInvoiceNo()
                        + " can be waived (what the student still owes, less sponsor shares and other waivers waiting)");
            }
        } else {
            BigDecimal room = refundable(s.getId(), credit(s.getId()));
            if (amount.compareTo(room) > 0) {
                throw new BusinessRuleException("REFUND_TOO_LARGE", room.signum() == 0 ? s.getRegistrationNo() + " has no credit to refund"
                        : "At most TZS " + tzs(room) + " of " + s.getRegistrationNo() + "'s credit can be refunded");
            }
        }
        FinanceAdjustment a = new FinanceAdjustment();
        a.setAdjustmentNo(number("ADJ"));
        a.setKind(req.kind());
        a.setStudentId(s.getId());
        a.setInvoiceId(inv == null ? null : inv.getId());
        a.setAmount(amount);
        a.setReason(req.reason().trim());
        a.setEvidenceRef(req.evidenceRef().trim());
        a.setRequestedById(CurrentUser.requireId());
        a.setRequestedByName(CurrentUser.name());
        a.setRequestedAt(OffsetDateTime.now());
        adjustments.saveAndFlush(a);
        audit.record(req.kind() + "_REQUESTED", "FinanceAdjustment", a.getId(), Map.of("no", a.getAdjustmentNo(), "student", s.getRegistrationNo(),
                "amount", amount));
        return view(a);
    }

    // ------------------------------------------------------------------ approval (BP-17 step 4)

    @Transactional
    public AdjustmentView approve(Long id, String note) {
        FinanceAdjustment a = require(id);
        Long me = CurrentUser.requireId();
        if (!canApprove(a, me)) throw new AccessDeniedException(waitingFor(a) == null ? "This request is already decided" : "Waiting for " + waitingFor(a));
        boolean twoLevels = a.getAmount().compareTo(threshold()) > 0;
        if (a.getStatus() == FinanceAdjustment.Status.PENDING) {
            a.setFirstById(me);
            a.setFirstByName(CurrentUser.name());
            a.setFirstAt(OffsetDateTime.now());
            if (twoLevels) {
                a.setStatus(FinanceAdjustment.Status.ENDORSED);
                a.setDecisionNote(blank(note));
                adjustments.saveAndFlush(a);
                audit.record(a.getKind() + "_ENDORSED", "FinanceAdjustment", a.getId(), Map.of("no", a.getAdjustmentNo(), "amount", a.getAmount()));
                return view(a);
            }
        } else {
            a.setSecondById(me);
            a.setSecondByName(CurrentUser.name());
            a.setSecondAt(OffsetDateTime.now());
        }
        a.setDecisionNote(blank(note) == null ? a.getDecisionNote() : blank(note));
        apply(a);
        return view(a);
    }

    @Transactional
    public AdjustmentView reject(Long id, String note) {
        FinanceAdjustment a = require(id);
        if (!canApprove(a, CurrentUser.requireId())) throw new AccessDeniedException("Not allowed to decide this request");
        if (blank(note) == null) throw new BusinessRuleException("NOTE_REQUIRED", "Give the reason for rejecting");
        a.setStatus(FinanceAdjustment.Status.REJECTED);
        a.setDecisionNote(note.trim());
        if (a.getFirstById() == null) {
            a.setFirstById(CurrentUser.requireId());
            a.setFirstByName(CurrentUser.name());
            a.setFirstAt(OffsetDateTime.now());
        } else {
            a.setSecondById(CurrentUser.requireId());
            a.setSecondByName(CurrentUser.name());
            a.setSecondAt(OffsetDateTime.now());
        }
        adjustments.saveAndFlush(a);
        Student s = students.findById(a.getStudentId()).orElseThrow();
        messaging.event("FIN_ADJUSTMENT_DECIDED", s.getPhone(), Map.of("kind", label(a.getKind()), "amount", "TZS " + tzs(a.getAmount()), "decision", "not approved"));
        audit.record(a.getKind() + "_REJECTED", "FinanceAdjustment", a.getId(), Map.of("no", a.getAdjustmentNo(), "note", note.trim()));
        return view(a);
    }

    /** The approved waiver is posted to the invoice as a credit note; the approved refund becomes a voucher and uses up the credit. */
    private void apply(FinanceAdjustment a) {
        Student s = students.findById(a.getStudentId()).orElseThrow();
        if (a.getKind() == FinanceAdjustment.Kind.WAIVER) {
            Invoice inv = invoices.findById(a.getInvoiceId()).orElseThrow();
            BigDecimal room = inv.balance().subtract(invoices.sponsorPending(inv.getId()));
            if (a.getAmount().compareTo(room) > 0) {
                throw new BusinessRuleException("WAIVER_TOO_LARGE", "The invoice now has only TZS " + tzs(room.max(BigDecimal.ZERO)) + " to waive; reject this request and record a new one");
            }
            a.setDocumentNo(number("CN"));
            billing.postDirect(inv, a.getAmount(), Payment.Source.INTERNAL, "WAIVER", a.getAdjustmentNo(), a.getDocumentNo(), OffsetDateTime.now());
        } else {
            BigDecimal left = a.getAmount();
            List<Payment> withCredit = studentPayments(s.getId()).stream().filter(p -> p.getUnallocatedAmount().signum() > 0)
                    .sorted(Comparator.comparing(Payment::getPaidAt)).toList();
            BigDecimal credit = withCredit.stream().map(Payment::getUnallocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (credit.compareTo(left) < 0) {
                throw new BusinessRuleException("REFUND_TOO_LARGE", "The student now has only TZS " + tzs(credit) + " of credit; reject this request and record a new one");
            }
            for (Payment p : withCredit) {
                if (left.signum() == 0) break;
                BigDecimal take = p.getUnallocatedAmount().min(left);
                p.setUnallocatedAmount(p.getUnallocatedAmount().subtract(take));
                left = left.subtract(take);
            }
            payments.saveAll(withCredit);
            a.setDocumentNo(number("RFV"));
        }
        a.setStatus(FinanceAdjustment.Status.APPROVED);
        adjustments.saveAndFlush(a);
        messaging.event("FIN_ADJUSTMENT_DECIDED", s.getPhone(), Map.of("kind", label(a.getKind()), "amount", "TZS " + tzs(a.getAmount()), "decision", "approved"));
        audit.record(a.getKind() + "_APPROVED", "FinanceAdjustment", a.getId(), Map.of("no", a.getAdjustmentNo(), "document", a.getDocumentNo(),
                "amount", a.getAmount(), "student", s.getRegistrationNo()));
    }

    @Transactional
    public AdjustmentView markPaid(Long id, String reference) {
        if (!has(Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT)) throw new AccessDeniedException("Only the bursary records refund payments");
        FinanceAdjustment a = require(id);
        if (a.getKind() != FinanceAdjustment.Kind.REFUND || a.getStatus() != FinanceAdjustment.Status.APPROVED) {
            throw new BusinessRuleException("NOT_PAYABLE", "Only an approved refund is paid out");
        }
        a.setStatus(FinanceAdjustment.Status.PAID);
        a.setPaidReference(reference.trim());
        a.setPaidAt(OffsetDateTime.now());
        adjustments.saveAndFlush(a);
        audit.record("REFUND_PAID", "FinanceAdjustment", a.getId(), Map.of("no", a.getAdjustmentNo(), "reference", reference.trim()));
        return view(a);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<AdjustmentView> list(boolean openOnly) {
        List<FinanceAdjustment> list = openOnly ? adjustments.findByStatusInOrderByRequestedAtAsc(OPEN) : adjustments.findTop300ByOrderByRequestedAtDesc();
        return list.stream().map(this::view).toList();
    }

    private AdjustmentView view(FinanceAdjustment a) {
        Student s = students.findById(a.getStudentId()).orElseThrow();
        String invoiceNo = a.getInvoiceId() == null ? null : invoices.findById(a.getInvoiceId()).map(Invoice::getInvoiceNo).orElse(null);
        Long me = CurrentUser.id().orElse(-1L);
        boolean can = canApprove(a, me);
        return new AdjustmentView(a.getId(), a.getAdjustmentNo(), a.getKind().name(), s.getId(), s.getRegistrationNo(), s.fullName(), a.getInvoiceId(),
                invoiceNo, a.getAmount(), a.getReason(), a.getEvidenceRef(), a.getStatus().name(), a.getAmount().compareTo(threshold()) > 0,
                a.getRequestedByName(), a.getRequestedAt(), a.getFirstByName(), a.getFirstAt(), a.getSecondByName(), a.getSecondAt(), a.getDecisionNote(),
                a.getDocumentNo(), a.getPaidReference(), a.getPaidAt(), waitingFor(a), can, can,
                a.getKind() == FinanceAdjustment.Kind.REFUND && a.getStatus() == FinanceAdjustment.Status.APPROVED
                        && has(Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT));
    }

    static String waitingFor(FinanceAdjustment a) {
        return switch (a.getStatus()) {
            case PENDING -> "the Bursar";
            case ENDORSED -> "the Principal (above the threshold)";
            default -> null;
        };
    }

    /** Level 1: the Bursar (or administrator); level 2: the Principal (or administrator). Never one's own request, never both levels. */
    boolean canApprove(FinanceAdjustment a, Long me) {
        if (Objects.equals(a.getRequestedById(), me)) return false;
        if (a.getStatus() == FinanceAdjustment.Status.PENDING) return has(Role.INSTITUTION_ADMIN, Role.BURSAR);
        if (a.getStatus() == FinanceAdjustment.Status.ENDORSED) return !Objects.equals(a.getFirstById(), me) && has(Role.INSTITUTION_ADMIN, Role.MANAGEMENT_VIEWER);
        return false;
    }

    private BigDecimal waivable(Invoice inv) {
        BigDecimal pending = adjustments.findByInvoiceIdAndStatusIn(inv.getId(), OPEN).stream().map(FinanceAdjustment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return inv.balance().subtract(invoices.sponsorPending(inv.getId())).subtract(pending).max(BigDecimal.ZERO);
    }

    private BigDecimal credit(Long studentId) {
        return studentPayments(studentId).stream().map(Payment::getUnallocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal refundable(Long studentId, BigDecimal credit) {
        BigDecimal pending = adjustments.findByStudentIdAndStatusIn(studentId, OPEN).stream().filter(x -> x.getKind() == FinanceAdjustment.Kind.REFUND)
                .map(FinanceAdjustment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return credit.subtract(pending).max(BigDecimal.ZERO);
    }

    private List<Payment> studentPayments(Long studentId) {
        List<Long> ids = invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.STUDENT, studentId).stream().map(Invoice::getId).toList();
        return ids.isEmpty() ? List.of() : payments.findByInvoiceIdInOrderByPaidAtAsc(ids);
    }

    private Student student(Long id, String regNo) {
        if (id != null) return students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
        if (regNo == null || regNo.isBlank()) throw new BusinessRuleException("STUDENT_REQUIRED", "Choose the student");
        return students.findByRegistrationNoIgnoreCase(regNo.trim()).orElseThrow(() -> new BusinessRuleException("UNKNOWN_STUDENT", "No student " + regNo.trim()));
    }

    private FinanceAdjustment require(Long id) {
        return adjustments.findById(id).orElseThrow(() -> new NotFoundException("Waiver or refund", id));
    }

    private String number(String kind) {
        int y = Year.now().getValue();
        return NumberSequenceService.format(TenantContext.require().code() + "/" + kind + "/" + y + "/", sequences.next(kind + "-" + y), 6);
    }

    private static boolean has(Role... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Set<String> mine = auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        for (Role r : roles) if (mine.contains(r.authority())) return true;
        return false;
    }

    private static String label(FinanceAdjustment.Kind k) {
        return k == FinanceAdjustment.Kind.WAIVER ? "waiver" : "refund";
    }

    static String tzs(BigDecimal v) {
        BigDecimal a = v.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(java.util.Locale.ENGLISH, "%,d", a.longValue()) : String.format(java.util.Locale.ENGLISH, "%,.2f", a);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
