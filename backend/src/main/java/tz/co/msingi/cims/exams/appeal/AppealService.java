package tz.co.msingi.cims.exams.appeal;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.AmendmentDecided;
import tz.co.msingi.cims.exams.AmendmentService;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.Grading;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.exams.ModuleResult;
import tz.co.msingi.cims.exams.RegulationService;
import tz.co.msingi.cims.exams.RegulationSet;
import tz.co.msingi.cims.exams.appeal.AppealDtos.AppealView;
import tz.co.msingi.cims.exams.appeal.AppealDtos.EligibleResult;
import tz.co.msingi.cims.exams.appeal.AppealDtos.ExaminerOption;
import tz.co.msingi.cims.exams.appeal.AppealDtos.MarkRequest;
import tz.co.msingi.cims.exams.appeal.AppealDtos.StudentAppeals;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Remarking and appeals (FR-EXM-12, BP-11). After results are published a student may, within the appeal period, ask for a
 * module's examination script to be re-marked or appeal the result, paying the fee per module by GePG. The Examinations Officer
 * assigns an examiner who did not teach the module (conflict of interest); the examiner records the new marks, the original
 * marks staying on record; the Examinations Officer forwards the outcome: unchanged closes the request, changed becomes a
 * result amendment for the administrator (someone else) to approve, which re-grades the student and recomputes their results.
 * The student is told the outcome by SMS.
 */
@Service
@RequiredArgsConstructor
public class AppealService {

    static final String PURPOSE = "APPEAL_FEES";
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Set<Role> EXAMINER_ROLES = Set.of(Role.LECTURER, Role.ASSESSOR, Role.HEAD_OF_DEPARTMENT, Role.PROGRAMME_COORDINATOR);

    private final ResultAppealRepository appeals;
    private final ModuleResultRepository moduleResults;
    private final ModuleOfferingRepository offerings;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final RegulationService regulations;
    private final AmendmentService amendments;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ student portal

    @Transactional(readOnly = true)
    public StudentAppeals mine() {
        Student st = me();
        var s = regulations.settings();
        List<ResultAppeal> mine = appeals.findByStudentIdOrderByRequestedAtDesc(st.getId());
        Set<Long> open = new java.util.HashSet<>();
        mine.stream().filter(a -> ResultAppeal.OPEN.contains(a.getStatus())).forEach(a -> open.add(a.getModuleResultId()));
        List<EligibleResult> eligible = eligible(st.getId(), s.getAppealWindowDays()).stream().filter(e -> !open.contains(e.moduleResultId())).toList();
        return new StudentAppeals(s.getAppealWindowDays(), s.getAppealFee(), eligible, mine.stream().map(a -> view(a, true)).toList());
    }

    @Transactional
    public StudentAppeals request(AppealDtos.AppealRequest req) {
        Student st = me();
        var s = regulations.settings();
        EligibleResult e = eligible(st.getId(), s.getAppealWindowDays()).stream().filter(x -> x.moduleResultId().equals(req.moduleResultId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("NOT_ELIGIBLE", s.getAppealWindowDays() == 0
                        ? "Remarks and appeals are not being accepted."
                        : "This result cannot be appealed: it is not published, or the appeal period of " + s.getAppealWindowDays() + " days has ended."));
        if (appeals.existsByModuleResultIdAndStatusIn(e.moduleResultId(), ResultAppeal.OPEN)) {
            throw new BusinessRuleException("ALREADY_REQUESTED", "You already have a request for this module in progress.");
        }
        ModuleResult m = moduleResults.findById(e.moduleResultId()).orElseThrow();
        ResultAppeal a = new ResultAppeal();
        a.setStudentId(st.getId());
        a.setModuleResultId(m.getId());
        a.setOfferingId(m.getOfferingId());
        a.setKind(req.kind());
        a.setReason(req.reason().trim());
        a.setFee(s.getAppealFee());
        a.setOriginalCaTotal(m.getCaTotal());
        a.setOriginalSeTotal(m.getSeTotal());
        a.setOriginalTotal(m.getTotal());
        a.setOriginalGrade(m.getGrade());
        a.setRequestedAt(OffsetDateTime.now());
        appeals.saveAndFlush(a);
        if (a.getFee().signum() > 0) {
            String label = (a.getKind() == ResultAppeal.Kind.REMARK ? "Remark of " : "Appeal in ") + e.moduleCode() + ", " + e.semester();
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, st.getId(), st.fullName(), st.getPhone(),
                    st.getEmail(), PURPOSE, a.getId(), label, List.of(new BillingService.NewInvoice.Line("APPEAL", label, a.getFee()))));
            a.setInvoiceId(inv.getId());
            billing.ensureControlNumber(inv.getId());
        }
        audit.record("APPEAL_REQUESTED", "ModuleResult", m.getId(), Map.of("student", st.getRegistrationNo(), "module", e.moduleCode(),
                "kind", a.getKind().name(), "fee", a.getFee()));
        return mine();
    }

    /** The student withdraws a request no examiner has been given yet; an unpaid invoice is cancelled. */
    @Transactional
    public StudentAppeals withdraw(Long id) {
        Student st = me();
        ResultAppeal a = appeals.findById(id).filter(x -> x.getStudentId().equals(st.getId())).orElseThrow(() -> new NotFoundException("Request", id));
        if (a.getStatus() != ResultAppeal.Status.REQUESTED) {
            throw new BusinessRuleException("CANNOT_WITHDRAW", "An examiner is already looking at this request; it can no longer be withdrawn.");
        }
        a.setStatus(ResultAppeal.Status.WITHDRAWN);
        a.setClosedAt(OffsetDateTime.now());
        if (a.getInvoiceId() != null) {
            invoices.findById(a.getInvoiceId()).filter(i -> i.getPaidAmount().signum() == 0 && i.getStatus() == Invoice.Status.ISSUED)
                    .ifPresent(i -> i.setStatus(Invoice.Status.CANCELLED));
        }
        audit.record("APPEAL_WITHDRAWN", "ModuleResult", a.getModuleResultId(), Map.of("student", st.getRegistrationNo()));
        return mine();
    }

    /**
     * The student's module results on published main result sheets, with the last day an appeal may be made (the publication date
     * plus the appeal period). A module also failed and resat is still listed: its first result is what is appealed.
     */
    private List<EligibleResult> eligible(Long studentId, int days) {
        if (days <= 0) return List.of();
        LocalDate today = LocalDate.now(ZONE);
        return jdbc.query("""
                select mr.id, mr.ca_total, mr.se_total, mr.total, mr.grade, mr.remark, m.code, m.title, se.name as semester, ay.code as year,
                       rs.published_at
                from module_results mr
                join module_offerings o on o.id = mr.offering_id
                join modules m on m.id = o.module_id
                join semesters se on se.id = o.semester_id
                join academic_years ay on ay.id = se.academic_year_id
                join semester_results sr on sr.student_id = mr.student_id
                join result_sheets rs on rs.id = sr.result_sheet_id and rs.semester_id = o.semester_id and rs.round = 'MAIN' and rs.status = 'PUBLISHED'
                where mr.student_id = ? and mr.locked
                order by rs.published_at desc, m.code""", (r, i) -> {
            OffsetDateTime published = r.getObject("published_at", OffsetDateTime.class);
            LocalDate last = published.atZoneSameInstant(ZONE).toLocalDate().plusDays(days);
            return new EligibleResult(r.getLong("id"), r.getString("code"), r.getString("title"), r.getString("semester") + " " + r.getString("year"),
                    r.getBigDecimal("ca_total"), r.getBigDecimal("se_total"), r.getBigDecimal("total"), r.getString("grade"), r.getString("remark"),
                    last.isBefore(today) ? null : last);
        }, studentId).stream().filter(e -> e.lastDay() != null).toList();
    }

    // ------------------------------------------------------------------ examinations office and examiners

    /**
     * Requests the signed-in person may see: all of them for the Examinations Office, the administrator and the Deputy Principal
     * (Academic); a Head of Department those for their department's modules; an examiner those assigned to them.
     */
    @Transactional(readOnly = true)
    public List<AppealView> list(boolean openOnly) {
        boolean office = access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER);
        Long me = CurrentUser.id().orElse(-1L);
        List<ResultAppeal> rows = openOnly ? appeals.findByStatusInOrderByRequestedAtAsc(ResultAppeal.OPEN) : appeals.findTop300ByOrderByRequestedAtDesc();
        List<AppealView> out = new ArrayList<>();
        for (ResultAppeal a : rows) {
            boolean mineToMark = Objects.equals(a.getExaminerUserId(), me);
            if (!office && !mineToMark) {
                ModuleOffering o = offerings.findById(a.getOfferingId()).orElseThrow();
                if (!access.moderates(o.getModule())) continue;
            }
            out.add(view(a, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AppealView get(Long id) {
        ResultAppeal a = appeal(id);
        requireSees(a);
        return view(a, false);
    }

    /** Staff who may examine the request: teaching staff who are not among the module's lecturers. */
    @Transactional(readOnly = true)
    public List<ExaminerOption> examiners(Long id) {
        access.require("assign an examiner", ExamAccess.EXAMS_OFFICE);
        ResultAppeal a = appeal(id);
        Set<Long> teaching = teachers(a.getOfferingId());
        return users.findStaff().stream()
                .filter(u -> u.getStatus() == UserAccount.Status.ACTIVE && !teaching.contains(u.getId()))
                .filter(u -> u.getRoles().stream().anyMatch(r -> EXAMINER_ROLES.contains(r.getRole())))
                .sorted(Comparator.comparing(UserAccount::getFullName))
                .map(u -> new ExaminerOption(u.getId(), u.getFullName(), u.getRoles().stream().map(r -> r.getRole().name()).sorted().toList()))
                .toList();
    }

    @Transactional
    public AppealView assign(Long id, Long examinerUserId) {
        access.require("assign an examiner", ExamAccess.EXAMS_OFFICE);
        ResultAppeal a = appeal(id);
        if (a.getStatus() != ResultAppeal.Status.REQUESTED && a.getStatus() != ResultAppeal.Status.ASSIGNED) throw state(a);
        if (!paid(a)) throw new BusinessRuleException("FEE_NOT_PAID", "The student has not paid the fee for this request yet.");
        if (teachers(a.getOfferingId()).contains(examinerUserId)) {
            throw new BusinessRuleException("CONFLICT_OF_INTEREST", "This person teaches the module; choose an examiner who did not.");
        }
        UserAccount u = users.findById(examinerUserId).filter(x -> x.getUserType() == UserAccount.UserType.STAFF && x.getStatus() == UserAccount.Status.ACTIVE
                && x.getRoles().stream().anyMatch(r -> EXAMINER_ROLES.contains(r.getRole())))
                .orElseThrow(() -> new BusinessRuleException("NOT_AN_EXAMINER", "Choose an active lecturer, assessor or Head of Department."));
        a.setExaminerUserId(u.getId());
        a.setExaminerName(u.getFullName());
        a.setAssignedByName(CurrentUser.name());
        a.setAssignedAt(OffsetDateTime.now());
        a.setStatus(ResultAppeal.Status.ASSIGNED);
        audit.record("APPEAL_EXAMINER_ASSIGNED", "ModuleResult", a.getModuleResultId(), Map.of("examiner", u.getFullName(), "appeal", a.getId()));
        return view(a, false);
    }

    /** The assigned examiner records the new marks. For a remark only the examination is marked again. */
    @Transactional
    public AppealView mark(Long id, MarkRequest req) {
        ResultAppeal a = appeal(id);
        if (!Objects.equals(a.getExaminerUserId(), CurrentUser.id().orElse(null))) {
            throw new AccessDeniedException("Only the examiner assigned to this request records its marks");
        }
        if (a.getStatus() != ResultAppeal.Status.ASSIGNED) throw state(a);
        ModuleResult m = moduleResults.findById(a.getModuleResultId()).orElseThrow();
        RegulationSet rs = amendments.regulationFor(m);
        BigDecimal ca = a.getKind() == ResultAppeal.Kind.REMARK || req.caTotal() == null ? nz(a.getOriginalCaTotal()) : req.caTotal();
        if (ca.compareTo(rs.getCaWeight()) > 0 || req.seTotal().compareTo(rs.getSeWeight()) > 0) {
            throw new BusinessRuleException("INVALID_SCORE", "CA is out of " + Grading.plain(rs.getCaWeight()) + " and SE out of " + Grading.plain(rs.getSeWeight()));
        }
        Grading.ModuleOutcome out = Grading.fromTotals(rs, ca, req.seTotal());
        a.setNewCaTotal(out.caTotal());
        a.setNewSeTotal(out.seTotal());
        a.setNewTotal(out.total());
        a.setNewGrade(out.grade());
        a.setExaminerComment(req.comment().trim());
        a.setMarkedAt(OffsetDateTime.now());
        a.setStatus(ResultAppeal.Status.MARKED);
        audit.record("APPEAL_MARKED", "ModuleResult", a.getModuleResultId(), Map.of("appeal", a.getId(), "from", String.valueOf(a.getOriginalTotal()),
                "to", String.valueOf(out.total()), "grade", out.grade()));
        return view(a, false);
    }

    /**
     * The Examinations Officer forwards the examiner's outcome: the same marks close the request as unchanged; changed marks
     * become an amendment of the confirmed result, approved by the administrator.
     */
    @Transactional
    public AppealView forward(Long id, String note) {
        access.require("forward a remark or appeal", ExamAccess.EXAMS_OFFICE);
        ResultAppeal a = appeal(id);
        if (a.getStatus() != ResultAppeal.Status.MARKED) throw state(a);
        boolean same = a.getNewTotal().compareTo(nz(a.getOriginalTotal())) == 0 && Objects.equals(a.getNewGrade(), a.getOriginalGrade())
                && a.getNewCaTotal().compareTo(nz(a.getOriginalCaTotal())) == 0;
        if (same) {
            close(a, ResultAppeal.Outcome.UNCHANGED, note, "the marks are unchanged (grade " + a.getOriginalGrade() + ")");
        } else {
            String ref = "APPEAL-" + a.getId();
            Long amendmentId = amendments.requestForAppeal(a.getModuleResultId(), a.getNewCaTotal(), a.getNewSeTotal(),
                    (a.getKind() == ResultAppeal.Kind.REMARK ? "Remark" : "Appeal") + " #" + a.getId() + " by " + a.getExaminerName() + ": "
                            + a.getExaminerComment(), ref);
            a.setAmendmentId(amendmentId);
            a.setDecisionNote(blank(note));
            a.setStatus(ResultAppeal.Status.AMENDMENT_PENDING);
            audit.record("APPEAL_FORWARDED", "ModuleResult", a.getModuleResultId(), Map.of("appeal", a.getId(), "amendment", amendmentId));
        }
        return view(a, false);
    }

    /** The Examinations Officer declines a request (e.g. outside the grounds for an appeal); the student is told why. */
    @Transactional
    public AppealView reject(Long id, String note) {
        access.require("decline a remark or appeal", ExamAccess.EXAMS_OFFICE);
        ResultAppeal a = appeal(id);
        if (a.getStatus() != ResultAppeal.Status.REQUESTED && a.getStatus() != ResultAppeal.Status.ASSIGNED) throw state(a);
        if (note == null || note.isBlank()) throw new BusinessRuleException("REASON_REQUIRED", "Give the reason for declining the request.");
        a.setStatus(ResultAppeal.Status.REJECTED);
        a.setDecisionNote(note.trim());
        a.setDecidedByName(CurrentUser.name());
        a.setClosedAt(OffsetDateTime.now());
        audit.record("APPEAL_REJECTED", "ModuleResult", a.getModuleResultId(), Map.of("appeal", a.getId(), "note", note.trim()));
        notifyStudent(a, "not accepted: " + note.trim());
        return view(a, false);
    }

    /** When the administrator decides the amendment a remark led to, the request closes with that outcome. */
    @EventListener
    public void amendmentDecided(AmendmentDecided e) {
        appeals.findByAmendmentId(e.amendmentId()).filter(a -> a.getStatus() == ResultAppeal.Status.AMENDMENT_PENDING).ifPresent(a -> {
            if (e.approved()) {
                a.setOutcome(ResultAppeal.Outcome.CHANGED);
                a.setStatus(ResultAppeal.Status.CLOSED);
                a.setDecidedByName(CurrentUser.name());
                a.setClosedAt(OffsetDateTime.now());
                audit.record("APPEAL_CLOSED", "ModuleResult", a.getModuleResultId(), Map.of("appeal", a.getId(), "outcome", "CHANGED", "grade", a.getNewGrade()));
                // the amendment's own SMS ("result corrected") tells the student
            } else {
                close(a, ResultAppeal.Outcome.UNCHANGED, e.note(), "the result stays as it was (grade " + a.getOriginalGrade() + ")");
            }
        });
    }

    private void close(ResultAppeal a, ResultAppeal.Outcome outcome, String note, String sms) {
        a.setOutcome(outcome);
        a.setStatus(ResultAppeal.Status.CLOSED);
        if (note != null && !note.isBlank()) a.setDecisionNote(note.trim());
        a.setDecidedByName(CurrentUser.name());
        a.setClosedAt(OffsetDateTime.now());
        audit.record("APPEAL_CLOSED", "ModuleResult", a.getModuleResultId(), Map.of("appeal", a.getId(), "outcome", outcome.name()));
        notifyStudent(a, sms);
    }

    private void notifyStudent(ResultAppeal a, String outcome) {
        Student st = students.findById(a.getStudentId()).orElseThrow();
        ModuleOffering o = offerings.findById(a.getOfferingId()).orElseThrow();
        messaging.event("APPEAL_OUTCOME", st.getPhone(), Map.of("module", o.getModule().getCode(), "outcome", outcome));
    }

    // ------------------------------------------------------------------ helpers

    private void requireSees(ResultAppeal a) {
        if (access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER)) return;
        if (Objects.equals(a.getExaminerUserId(), CurrentUser.id().orElse(null))) return;
        ModuleOffering o = offerings.findById(a.getOfferingId()).orElseThrow();
        if (access.moderates(o.getModule())) return;
        throw new AccessDeniedException("Not allowed to see this request");
    }

    private Set<Long> teachers(Long offeringId) {
        return new java.util.HashSet<>(jdbc.queryForList("select user_id from offering_staff where offering_id = ?", Long.class, offeringId));
    }

    private boolean paid(ResultAppeal a) {
        if (a.getFee().signum() == 0 || a.getInvoiceId() == null) return true;
        return invoices.findById(a.getInvoiceId()).map(i -> i.getStatus() == Invoice.Status.PAID).orElse(false);
    }

    AppealView view(ResultAppeal a, boolean forStudent) {
        Student st = students.findById(a.getStudentId()).orElseThrow();
        ModuleOffering o = offerings.findById(a.getOfferingId()).orElseThrow();
        ModuleResult m = moduleResults.findById(a.getModuleResultId()).orElseThrow();
        RegulationSet rs = amendments.regulationFor(m);
        boolean paid = paid(a);
        Invoice inv = a.getInvoiceId() == null ? null : invoices.findById(a.getInvoiceId()).orElse(null);
        ControlNumber cn = inv == null || paid ? null : billing.currentControlNumber(inv.getId());
        boolean office = !forStudent && access.has(ExamAccess.EXAMS_OFFICE);
        boolean examiner = !forStudent && Objects.equals(a.getExaminerUserId(), CurrentUser.id().orElse(null));
        boolean showExaminer = !forStudent;
        return new AppealView(a.getId(), st.getId(), st.getRegistrationNo(), st.fullName(), st.getProgramme().getCode(), m.getId(),
                o.getModule().getCode(), o.getModule().getTitle(), o.getSemester().getName() + " " + o.getSemester().getAcademicYear().getCode(),
                a.getKind().name(), a.getReason(), a.getFee(), paid, inv == null ? null : inv.getInvoiceNo(), cn == null ? null : cn.getControlNo(),
                a.getStatus().name(), a.getOutcome() == null ? null : a.getOutcome().name(), a.getOriginalCaTotal(), a.getOriginalSeTotal(),
                a.getOriginalTotal(), a.getOriginalGrade(), showExaminer ? a.getExaminerName() : null, showExaminer ? a.getAssignedByName() : null,
                a.getAssignedAt(), forStudent && a.getStatus() != ResultAppeal.Status.CLOSED ? null : a.getNewCaTotal(),
                forStudent && a.getStatus() != ResultAppeal.Status.CLOSED ? null : a.getNewSeTotal(),
                forStudent && a.getStatus() != ResultAppeal.Status.CLOSED ? null : a.getNewTotal(),
                forStudent && a.getStatus() != ResultAppeal.Status.CLOSED ? null : a.getNewGrade(),
                showExaminer ? a.getExaminerComment() : null, a.getMarkedAt(), a.getAmendmentId(), a.getDecisionNote(), a.getDecidedByName(),
                a.getClosedAt(), a.getRequestedAt(),
                office && paid && (a.getStatus() == ResultAppeal.Status.REQUESTED || a.getStatus() == ResultAppeal.Status.ASSIGNED),
                examiner && a.getStatus() == ResultAppeal.Status.ASSIGNED,
                office && a.getStatus() == ResultAppeal.Status.MARKED,
                office && (a.getStatus() == ResultAppeal.Status.REQUESTED || a.getStatus() == ResultAppeal.Status.ASSIGNED),
                forStudent && a.getStatus() == ResultAppeal.Status.REQUESTED, rs.getCaWeight(), rs.getSeWeight());
    }

    private ResultAppeal appeal(Long id) {
        return appeals.findById(id).orElseThrow(() -> new NotFoundException("Remark or appeal", id));
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private static BusinessRuleException state(ResultAppeal a) {
        return new BusinessRuleException("INVALID_STATE", "This request is " + a.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
