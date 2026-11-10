package tz.co.msingi.cims.welfare;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Discipline and welfare (FR-WEL-01..04). A disciplinary case is reported by any member of staff, heard (the student is called by SMS
 * and may send a written statement), decided by the Dean of Students, and may be appealed by the student within 14 days to the
 * Principal, who is not the person who decided. A fine or compensation goes on the student's fees account with a control number.
 * Counselling requests come from the student or are referred by staff; only the Dean of Students reads them, and what is written
 * is kept encrypted. The administrator and management see only counts.
 */
@Service
@RequiredArgsConstructor
public class WelfareService {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    public static final String PURPOSE = "DISCIPLINE_CHARGES";
    static final int APPEAL_DAYS = 14;
    static final Role[] MANAGE = {Role.INSTITUTION_ADMIN, Role.DEAN_OF_STUDENTS};
    static final Role[] READ = {Role.INSTITUTION_ADMIN, Role.DEAN_OF_STUDENTS, Role.MANAGEMENT_VIEWER, Role.AUDITOR};
    static final Role[] APPEALS = {Role.INSTITUTION_ADMIN, Role.MANAGEMENT_VIEWER};
    static final Set<String> CATEGORIES = Set.of("MISCONDUCT", "ABSENTEEISM", "PROPERTY_DAMAGE", "VIOLENCE", "SUBSTANCE_ABUSE", "HARASSMENT", "THEFT",
            "HOSTEL_RULES", "OTHER");
    static final Set<String> OUTCOMES = Set.of("NONE", "WARNING", "REPRIMAND", "FINE", "COMPENSATION", "COMMUNITY_SERVICE", "SUSPENSION", "EXPULSION");
    static final Set<String> CONCERNS = Set.of("ACADEMIC", "FINANCIAL", "HEALTH", "FAMILY", "PERSONAL", "OTHER");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final ExamAccess access;
    private final StudentRepository students;
    private final BillingService billing;
    private final NumberSequenceService sequences;
    private final SecretCipher cipher;
    private final Messaging messaging;
    private final AuditService audit;

    public record ReportRequest(String registrationNo, String category, LocalDate occurredOn, String place, String description) {
    }

    public record HearingRequest(OffsetDateTime hearingAt, String place, String committee, Boolean tellStudent) {
    }

    public record DecisionRequest(String finding, String outcome, String details, BigDecimal amount, LocalDate suspendedFrom, LocalDate suspendedTo) {
    }

    public record AppealDecisionRequest(String result, String note, String outcome, String details, BigDecimal amount, LocalDate suspendedFrom,
                                        LocalDate suspendedTo) {
    }

    public record CaseView(Long id, String caseNo, Long studentId, String registrationNo, String studentName, String programme, String category,
                           LocalDate occurredOn, String place, String description, String reportedByName, OffsetDateTime reportedAt, String status,
                           OffsetDateTime hearingAt, String hearingPlace, String committee, String studentStatement, String finding, String outcome,
                           String outcomeDetails, BigDecimal amount, LocalDate suspendedFrom, LocalDate suspendedTo, String invoiceStatus, String controlNo,
                           String decidedByName, OffsetDateTime decidedAt, LocalDate appealBy, String appealText, OffsetDateTime appealedAt,
                           String appealResult, String appealNote, String appealDecidedByName, OffsetDateTime appealDecidedAt, int earlierCases) {
    }

    public record Overview(int open, int hearingsThisWeek, int appeals, Map<String, Integer> outcomesThisYear, int counsellingOpen, int counsellingNew) {
    }

    public record MyCase(Long id, String caseNo, String category, LocalDate occurredOn, String status, OffsetDateTime hearingAt, String hearingPlace,
                         String studentStatement, String outcome, String outcomeDetails, BigDecimal amount, String controlNo, LocalDate suspendedFrom,
                         LocalDate suspendedTo, LocalDate appealBy, boolean canAppeal, String appealText, String appealResult, String appealNote) {
    }

    public record ReferralRequest(String registrationNo, String concern, String text, String contactPhone) {
    }

    public record CounsellingSummary(Long id, Long studentId, String registrationNo, String studentName, String concern, boolean selfReferred,
                                     String referredByName, String status, OffsetDateTime nextSessionAt, OffsetDateTime openedAt, int notes) {
    }

    public record CounsellingNote(Long id, String note, String byName, OffsetDateTime at) {
    }

    public record CounsellingCase(CounsellingSummary summary, String requestText, String contactPhone, String studentPhone, List<CounsellingNote> notes) {
    }

    public record MyRequest(Long id, String concern, String status, OffsetDateTime nextSessionAt, OffsetDateTime openedAt) {
    }

    // ================================================================== discipline

    @Transactional
    public CaseView report(ReportRequest r) {
        Student s = studentByRegNo(r.registrationNo());
        String category = oneOf(r.category(), CATEGORIES, "what kind of offence it is");
        if (r.occurredOn() == null || r.occurredOn().isAfter(LocalDate.now(TZ))) throw new BusinessRuleException("DATE_INVALID", "Give the date it happened");
        String text = required(r.description(), 2000, "what happened");
        String no = NumberSequenceService.format(TenantContext.require().code() + "/DC/" + LocalDate.now(TZ).getYear() + "/",
                sequences.next("DISCIPLINE_CASE"), 4);
        Long id = jdbc.queryForObject("""
                insert into discipline_cases (tenant_id, case_no, student_id, category, occurred_on, place, description, reported_by, reported_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, no, s.getId(), category, r.occurredOn(),
                cut(r.place(), 150), text, CurrentUser.id().orElse(null), CurrentUser.name());
        audit.record("DISCIPLINE_REPORTED", "DisciplineCase", id, Map.of("caseNo", no, "student", s.getRegistrationNo()));
        return get(id);
    }

    /** The cases: everyone for the Dean, the administrator, management and the auditor; for anyone else, the cases they reported. */
    @Transactional(readOnly = true)
    public List<CaseView> cases(String status, String q) {
        boolean all = access.has(READ);
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(CASE + " " + """
                 where (?::text is null or c.status = ? or (? = 'OPEN' and c.status in ('REPORTED', 'HEARING', 'APPEALED')))
                   and (lower(s.registration_no) like ? or lower(s.first_name || ' ' || s.surname) like ? or lower(c.case_no) like ?)
                   and (? or c.reported_by = ?) order by c.reported_at desc limit 500""", (rs, n) -> view(rs), blank(status), blank(status), blank(status),
                like, like, like, all, CurrentUser.id().orElse(-1L));
    }

    @Transactional(readOnly = true)
    public CaseView get(Long id) {
        CaseView c = jdbc.query(CASE + " where c.id = ?", (rs, n) -> view(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Case not found"));
        boolean reporter = Objects.equals(jdbc.queryForObject("select reported_by from discipline_cases where id = ?", Long.class, id), CurrentUser.id().orElse(null));
        if (!access.has(READ) && !reporter) throw new AccessDeniedException("Not allowed to read this case");
        return c;
    }

    @Transactional
    public CaseView setHearing(Long id, HearingRequest r) {
        access.require("call a disciplinary hearing", MANAGE);
        Map<String, Object> c = lock(id);
        if (!Set.of("REPORTED", "HEARING").contains((String) c.get("status"))) throw new BusinessRuleException("CASE_DECIDED", "This case is already decided");
        if (r.hearingAt() == null || r.hearingAt().toInstant().isBefore(java.time.Instant.now())) {
            throw new BusinessRuleException("DATE_INVALID", "Give the date and time of the hearing, in the future");
        }
        String place = required(r.place(), 150, "where the hearing is held");
        jdbc.update("update discipline_cases set status = 'HEARING', hearing_at = ?, hearing_place = ?, committee = ? where id = ?",
                Timestamp.from(r.hearingAt().toInstant()), place, cut(r.committee(), 500), id);
        if (!Boolean.FALSE.equals(r.tellStudent())) {
            Student s = students.findById(((Number) c.get("student_id")).longValue()).orElseThrow();
            messaging.event("DISCIPLINE_HEARING", s.getPhone(), Map.of("caseNo", c.get("case_no"), "date", WHEN.format(r.hearingAt().atZoneSameInstant(TZ)),
                    "place", place));
        }
        audit.record("DISCIPLINE_HEARING_SET", "DisciplineCase", id, Map.of("at", r.hearingAt().toString()));
        return get(id);
    }

    @Transactional
    public CaseView decide(Long id, DecisionRequest r) {
        access.require("decide a disciplinary case", MANAGE);
        Map<String, Object> c = lock(id);
        if (!"HEARING".equals(c.get("status"))) {
            throw new BusinessRuleException(Set.of("REPORTED").contains((String) c.get("status")) ? "HEARING_REQUIRED" : "CASE_DECIDED",
                    "REPORTED".equals(c.get("status")) ? "The student is heard before the case is decided; set the hearing first" : "This case is already decided");
        }
        String finding = oneOf(r.finding(), Set.of("GUILTY", "NOT_GUILTY"), "the finding");
        Outcome o = "NOT_GUILTY".equals(finding) ? new Outcome("NONE", cut(r.details(), 1000), null, null, null)
                : outcome(r.outcome(), r.details(), r.amount(), r.suspendedFrom(), r.suspendedTo());
        if ("GUILTY".equals(finding) && "NONE".equals(o.outcome())) throw new BusinessRuleException("OUTCOME_REQUIRED", "Choose the penalty");
        jdbc.update("""
                update discipline_cases set status = 'DECIDED', finding = ?, outcome = ?, outcome_details = ?, amount = ?, suspended_from = ?, suspended_to = ?,
                  decided_by = ?, decided_by_name = ?, decided_at = now() where id = ?""", finding, o.outcome(), o.details(), o.amount(), o.from(), o.to(),
                CurrentUser.id().orElse(null), CurrentUser.name(), id);
        Long studentId = ((Number) c.get("student_id")).longValue();
        String controlNo = o.amount() == null ? null : charge(id, studentId, (String) c.get("case_no"), o.outcome(), o.amount());
        Student s = students.findById(studentId).orElseThrow();
        messaging.event("DISCIPLINE_DECISION", s.getPhone(), Map.of("caseNo", c.get("case_no"), "outcome", outcomeText(finding, o, controlNo),
                "days", String.valueOf(APPEAL_DAYS)));
        audit.record("DISCIPLINE_DECIDED", "DisciplineCase", id, Map.of("finding", finding, "outcome", o.outcome()));
        return get(id);
    }

    @Transactional
    public CaseView withdraw(Long id, String note) {
        access.require("withdraw a disciplinary case", MANAGE);
        Map<String, Object> c = lock(id);
        if (!Set.of("REPORTED", "HEARING").contains((String) c.get("status"))) throw new BusinessRuleException("CASE_DECIDED", "A decided case is not withdrawn");
        jdbc.update("update discipline_cases set status = 'WITHDRAWN', outcome_details = ?, decided_by_name = ?, decided_at = now() where id = ?",
                required(note, 1000, "why the case is withdrawn"), CurrentUser.name(), id);
        audit.record("DISCIPLINE_WITHDRAWN", "DisciplineCase", id, Map.of());
        return get(id);
    }

    /** The Principal (or the administrator) decides the student's appeal; never the person who decided the case. */
    @Transactional
    public CaseView decideAppeal(Long id, AppealDecisionRequest r) {
        access.require("decide an appeal", APPEALS);
        Map<String, Object> c = lock(id);
        if (!"APPEALED".equals(c.get("status"))) throw new BusinessRuleException("NO_APPEAL", "There is no appeal waiting in this case");
        if (c.get("decided_by") != null && Objects.equals(((Number) c.get("decided_by")).longValue(), CurrentUser.id().orElse(null))) throw new BusinessRuleException("DECIDED_BY_YOU", "You decided this case; someone else hears the appeal");
        String result = oneOf(r.result(), Set.of("UPHELD", "VARIED", "SET_ASIDE"), "the result of the appeal");
        String note = required(r.note(), 1000, "the reasons");
        Long studentId = ((Number) c.get("student_id")).longValue();
        Long invoiceId = c.get("invoice_id") == null ? null : ((Number) c.get("invoice_id")).longValue();
        String controlNo = null;
        if (!"UPHELD".equals(result)) {
            if (invoiceId != null && !billing.cancelIfUnpaid(invoiceId, "Discipline appeal " + result.toLowerCase(Locale.ROOT).replace('_', ' '))) {
                throw new BusinessRuleException("ALREADY_PAID", "The charge is already paid; refund it through Waivers & refunds first");
            }
            if ("SET_ASIDE".equals(result)) {
                jdbc.update("update discipline_cases set outcome = 'NONE', finding = 'NOT_GUILTY', amount = null, suspended_from = null, suspended_to = null, "
                        + "invoice_id = null where id = ?", id);
            } else {
                Outcome o = outcome(r.outcome(), r.details(), r.amount(), r.suspendedFrom(), r.suspendedTo());
                jdbc.update("update discipline_cases set outcome = ?, outcome_details = ?, amount = ?, suspended_from = ?, suspended_to = ?, invoice_id = null "
                        + "where id = ?", o.outcome(), o.details(), o.amount(), o.from(), o.to(), id);
                if (o.amount() != null) controlNo = charge(id, studentId, (String) c.get("case_no"), o.outcome(), o.amount());
            }
        }
        jdbc.update("update discipline_cases set status = 'CLOSED', appeal_result = ?, appeal_note = ?, appeal_decided_by_name = ?, appeal_decided_at = now() "
                + "where id = ?", result, note, CurrentUser.name(), id);
        Student s = students.findById(studentId).orElseThrow();
        String text = switch (result) {
            case "UPHELD" -> "appeal not allowed; the decision stands";
            case "SET_ASIDE" -> "appeal allowed; the decision is set aside";
            default -> "appeal allowed in part; the penalty is changed" + (controlNo == null ? "" : ", pay with number " + controlNo);
        };
        messaging.event("DISCIPLINE_DECISION", s.getPhone(), Map.of("caseNo", c.get("case_no"), "outcome", text, "days", "0"));
        audit.record("DISCIPLINE_APPEAL_DECIDED", "DisciplineCase", id, Map.of("result", result));
        return get(id);
    }

    @Transactional
    public CaseView close(Long id) {
        access.require("close a disciplinary case", MANAGE);
        Map<String, Object> c = lock(id);
        if (!"DECIDED".equals(c.get("status"))) throw new BusinessRuleException("CASE_NOT_DECIDED", "Only a decided case is closed");
        jdbc.update("update discipline_cases set status = 'CLOSED' where id = ?", id);
        audit.record("DISCIPLINE_CLOSED", "DisciplineCase", id, Map.of());
        return get(id);
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        access.require("see the welfare overview", READ);
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        jdbc.query("""
                select outcome, count(*) from discipline_cases where outcome is not null and decided_at >= date_trunc('year', now()) group by outcome order by 2 desc""",
                rs -> {
                    outcomes.put(rs.getString(1), rs.getInt(2));
                });
        return jdbc.queryForObject("""
                select (select count(*) from discipline_cases where status in ('REPORTED', 'HEARING', 'APPEALED')),
                       (select count(*) from discipline_cases where status = 'HEARING' and hearing_at >= now() and hearing_at < now() + interval '7 days'),
                       (select count(*) from discipline_cases where status = 'APPEALED'),
                       (select count(*) from counselling_cases where status = 'OPEN'),
                       (select count(*) from counselling_cases where status = 'OPEN' and next_session_at is null)""",
                (rs, n) -> new Overview(rs.getInt(1), rs.getInt(2), rs.getInt(3), outcomes, rs.getInt(4), rs.getInt(5)));
    }

    // ------------------------------------------------------------------ the student's side

    @Transactional(readOnly = true)
    public List<MyCase> myCases() {
        Long me = myStudentId();
        return jdbc.query(CASE + " where c.student_id = ? and c.status <> 'WITHDRAWN' order by c.reported_at desc", (rs, n) -> {
            CaseView v = view(rs);
            boolean canAppeal = "DECIDED".equals(v.status()) && "GUILTY".equals(v.finding()) && v.appealBy() != null && !LocalDate.now(TZ).isAfter(v.appealBy());
            return new MyCase(v.id(), v.caseNo(), v.category(), v.occurredOn(), v.status(), v.hearingAt(), v.hearingPlace(), v.studentStatement(), v.outcome(),
                    v.outcomeDetails(), v.amount(), v.controlNo(), v.suspendedFrom(), v.suspendedTo(), v.appealBy(), canAppeal, v.appealText(), v.appealResult(),
                    v.appealNote());
        }, me);
    }

    @Transactional
    public List<MyCase> statement(Long id, String text) {
        Long me = myStudentId();
        Map<String, Object> c = lock(id);
        if (!Objects.equals(((Number) c.get("student_id")).longValue(), me)) throw new NotFoundException("Case not found");
        if (!Set.of("REPORTED", "HEARING").contains((String) c.get("status"))) throw new BusinessRuleException("CASE_DECIDED", "The case is already decided");
        jdbc.update("update discipline_cases set student_statement = ? where id = ?", required(text, 2000, "your statement"), id);
        audit.record("DISCIPLINE_STATEMENT", "DisciplineCase", id, Map.of());
        return myCases();
    }

    @Transactional
    public List<MyCase> appeal(Long id, String text) {
        Long me = myStudentId();
        Map<String, Object> c = lock(id);
        if (!Objects.equals(((Number) c.get("student_id")).longValue(), me)) throw new NotFoundException("Case not found");
        if (!"DECIDED".equals(c.get("status")) || !"GUILTY".equals(c.get("finding"))) throw new BusinessRuleException("NOT_APPEALABLE", "There is nothing to appeal in this case");
        LocalDate decided = ((Timestamp) c.get("decided_at")).toInstant().atZone(TZ).toLocalDate();
        if (LocalDate.now(TZ).isAfter(decided.plusDays(APPEAL_DAYS))) {
            throw new BusinessRuleException("APPEAL_LATE", "Appeals are made within " + APPEAL_DAYS + " days of the decision");
        }
        jdbc.update("update discipline_cases set status = 'APPEALED', appeal_text = ?, appealed_at = now() where id = ?", required(text, 2000, "the grounds of your appeal"), id);
        audit.record("DISCIPLINE_APPEALED", "DisciplineCase", id, Map.of());
        return myCases();
    }

    // ================================================================== counselling

    @Transactional
    public List<MyRequest> askForCounselling(ReferralRequest r) {
        Long me = myStudentId();
        String concern = oneOf(r.concern(), CONCERNS, "what it is about");
        Integer open = jdbc.queryForObject("select count(*) from counselling_cases where student_id = ? and status = 'OPEN'", Integer.class, me);
        if (open != null && open > 0) throw new BusinessRuleException("REQUEST_OPEN", "Your earlier request is still open; the Dean of Students will contact you");
        String phone = phone(r.contactPhone());
        jdbc.update("""
                insert into counselling_cases (tenant_id, student_id, concern, self_referred, request_text_enc, contact_phone)
                values (cims_current_tenant(), ?, ?, true, ?, ?)""", me, concern, r.text() == null || r.text().isBlank() ? null : cipher.encrypt(cut(r.text(), 2000)),
                phone);
        audit.record("COUNSELLING_REQUESTED", "CounsellingCase", null, Map.of());
        return myRequests();
    }

    @Transactional(readOnly = true)
    public List<MyRequest> myRequests() {
        Long me = myStudentId();
        return jdbc.query("select id, concern, status, next_session_at, opened_at from counselling_cases where student_id = ? "
                        + "order by status desc, next_session_at nulls last, opened_at desc limit 10",
                (rs, n) -> new MyRequest(rs.getLong(1), rs.getString(2), rs.getString(3), odt(rs.getTimestamp(4)), odt(rs.getTimestamp(5))), me);
    }

    /** A member of staff refers a student; they do not see the case afterwards. */
    @Transactional
    public void refer(ReferralRequest r) {
        Student s = studentByRegNo(r.registrationNo());
        String concern = oneOf(r.concern(), CONCERNS, "what it is about");
        String text = required(r.text(), 2000, "why you refer the student");
        jdbc.update("""
                insert into counselling_cases (tenant_id, student_id, concern, self_referred, referred_by_name, request_text_enc)
                values (cims_current_tenant(), ?, ?, false, ?, ?)""", s.getId(), concern, CurrentUser.name(), cipher.encrypt(text));
        audit.record("COUNSELLING_REFERRED", "CounsellingCase", null, Map.of());
    }

    @Transactional(readOnly = true)
    public List<CounsellingSummary> counselling(String status) {
        access.require("read counselling cases", Role.DEAN_OF_STUDENTS);
        return jdbc.query(COUNSEL + " where (?::text is null or c.status = ?) order by c.status desc, c.opened_at desc limit 500",
                (rs, n) -> summary(rs), blank(status), blank(status));
    }

    @Transactional
    public CounsellingCase counsellingCase(Long id) {
        access.require("read counselling cases", Role.DEAN_OF_STUDENTS);
        CounsellingSummary s = jdbc.query(COUNSEL + " where c.id = ?", (rs, n) -> summary(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Case not found"));
        Map<String, Object> c = jdbc.queryForMap("""
                select c.request_text_enc, c.contact_phone, st.phone from counselling_cases c join students st on st.id = c.student_id where c.id = ?""", id);
        List<CounsellingNote> notes = jdbc.query("select id, note_enc, by_name, at from counselling_notes where case_id = ? order by at",
                (rs, n) -> new CounsellingNote(rs.getLong(1), cipher.decrypt(rs.getString(2)), rs.getString(3), odt(rs.getTimestamp(4))), id);
        audit.record("COUNSELLING_READ", "CounsellingCase", id, Map.of());
        String enc = (String) c.get("request_text_enc");
        return new CounsellingCase(s, enc == null ? null : cipher.decrypt(enc), (String) c.get("contact_phone"), (String) c.get("phone"), notes);
    }

    @Transactional
    public CounsellingCase addNote(Long id, String note) {
        access.require("write counselling notes", Role.DEAN_OF_STUDENTS);
        counsellingExists(id);
        jdbc.update("insert into counselling_notes (tenant_id, case_id, note_enc, by_name) values (cims_current_tenant(), ?, ?, ?)", id,
                cipher.encrypt(required(note, 5000, "the note")), CurrentUser.name());
        audit.record("COUNSELLING_NOTE_ADDED", "CounsellingCase", id, Map.of());
        return counsellingCase(id);
    }

    @Transactional
    public CounsellingCase appointment(Long id, OffsetDateTime at, boolean tell) {
        access.require("set counselling appointments", Role.DEAN_OF_STUDENTS);
        counsellingExists(id);
        if (at == null || at.toInstant().isBefore(java.time.Instant.now())) throw new BusinessRuleException("DATE_INVALID", "Give a date and time in the future");
        jdbc.update("update counselling_cases set next_session_at = ?, status = 'OPEN', closed_at = null where id = ?", Timestamp.from(at.toInstant()), id);
        if (tell) {
            Map<String, Object> c = jdbc.queryForMap("select coalesce(c.contact_phone, s.phone) p from counselling_cases c join students s on s.id = c.student_id where c.id = ?", id);
            messaging.event("COUNSELLING_APPOINTMENT", (String) c.get("p"), Map.of("date", WHEN.format(at.atZoneSameInstant(TZ))));
        }
        audit.record("COUNSELLING_APPOINTMENT", "CounsellingCase", id, Map.of());
        return counsellingCase(id);
    }

    @Transactional
    public CounsellingCase setOpen(Long id, boolean open) {
        access.require("close counselling cases", Role.DEAN_OF_STUDENTS);
        counsellingExists(id);
        jdbc.update("update counselling_cases set status = ?, closed_at = case when ? then null else now() end, next_session_at = case when ? then next_session_at end "
                + "where id = ?", open ? "OPEN" : "CLOSED", open, open, id);
        audit.record(open ? "COUNSELLING_REOPENED" : "COUNSELLING_CLOSED", "CounsellingCase", id, Map.of());
        return counsellingCase(id);
    }

    // ------------------------------------------------------------------ helpers

    private record Outcome(String outcome, String details, BigDecimal amount, LocalDate from, LocalDate to) {
    }

    private static Outcome outcome(String outcome, String details, BigDecimal amount, LocalDate from, LocalDate to) {
        String o = oneOf(outcome, OUTCOMES, "the penalty");
        BigDecimal a = null;
        if (Set.of("FINE", "COMPENSATION").contains(o)) {
            if (amount == null || amount.signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "Give the amount to pay");
            a = amount.setScale(2, RoundingMode.HALF_UP);
        }
        if ("SUSPENSION".equals(o) && (from == null || to == null || to.isBefore(from))) {
            throw new BusinessRuleException("DATES_REQUIRED", "Give the first and last day of the suspension");
        }
        if (Set.of("COMMUNITY_SERVICE", "EXPULSION", "SUSPENSION").contains(o) && (details == null || details.isBlank()) && !"SUSPENSION".equals(o)) {
            throw new BusinessRuleException("DETAILS_REQUIRED", "Describe the penalty");
        }
        return new Outcome(o, cut(details, 1000), a, "SUSPENSION".equals(o) ? from : null, "SUSPENSION".equals(o) ? to : null);
    }

    private static String outcomeText(String finding, Outcome o, String controlNo) {
        if ("NOT_GUILTY".equals(finding)) return "not found responsible; the case is closed";
        String base = switch (o.outcome()) {
            case "WARNING" -> "a warning";
            case "REPRIMAND" -> "a reprimand";
            case "FINE" -> "a fine of TZS " + o.amount().setScale(0, RoundingMode.HALF_UP).toPlainString();
            case "COMPENSATION" -> "compensation of TZS " + o.amount().setScale(0, RoundingMode.HALF_UP).toPlainString();
            case "COMMUNITY_SERVICE" -> "community service";
            case "SUSPENSION" -> "suspension from " + o.from() + " to " + o.to();
            case "EXPULSION" -> "expulsion";
            default -> "no penalty";
        };
        return base + (controlNo == null ? "" : ", pay with number " + controlNo);
    }

    private String charge(Long caseId, Long studentId, String caseNo, String outcome, BigDecimal amount) {
        Student s = students.findById(studentId).orElseThrow();
        String what = ("FINE".equals(outcome) ? "Disciplinary fine" : "Compensation for damage") + ", case " + caseNo;
        Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(),
                PURPOSE, caseId, what, List.of(new BillingService.NewInvoice.Line("DISCIPLINE", what, amount))));
        ControlNumber cn = billing.ensureControlNumber(inv.getId());
        jdbc.update("update discipline_cases set invoice_id = ? where id = ?", inv.getId(), caseId);
        return cn == null ? null : cn.getControlNo();
    }

    private static final String CASE = """
            select c.*, s.registration_no, s.first_name || ' ' || s.surname as student_name, p.code as programme,
                   i.status as invoice_status, (select cn.control_no from control_numbers cn where cn.invoice_id = c.invoice_id order by cn.id desc limit 1) as control_no,
                   (select count(*) from discipline_cases x where x.student_id = c.student_id and x.id <> c.id and x.finding = 'GUILTY') as earlier
            from discipline_cases c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
            left join invoices i on i.id = c.invoice_id""";

    private static CaseView view(ResultSet rs) throws SQLException {
        OffsetDateTime decided = odt(rs.getTimestamp("decided_at"));
        LocalDate appealBy = decided == null || !"GUILTY".equals(rs.getString("finding")) ? null
                : decided.atZoneSameInstant(TZ).toLocalDate().plusDays(APPEAL_DAYS);
        return new CaseView(rs.getLong("id"), rs.getString("case_no"), rs.getLong("student_id"), rs.getString("registration_no"), rs.getString("student_name"),
                rs.getString("programme"), rs.getString("category"), rs.getObject("occurred_on", LocalDate.class), rs.getString("place"),
                rs.getString("description"), rs.getString("reported_by_name"), odt(rs.getTimestamp("reported_at")), rs.getString("status"),
                odt(rs.getTimestamp("hearing_at")), rs.getString("hearing_place"), rs.getString("committee"), rs.getString("student_statement"),
                rs.getString("finding"), rs.getString("outcome"), rs.getString("outcome_details"), rs.getBigDecimal("amount"),
                rs.getObject("suspended_from", LocalDate.class), rs.getObject("suspended_to", LocalDate.class), rs.getString("invoice_status"),
                rs.getString("control_no"), rs.getString("decided_by_name"), decided, appealBy, rs.getString("appeal_text"),
                odt(rs.getTimestamp("appealed_at")), rs.getString("appeal_result"), rs.getString("appeal_note"), rs.getString("appeal_decided_by_name"),
                odt(rs.getTimestamp("appeal_decided_at")), rs.getInt("earlier"));
    }

    private static final String COUNSEL = """
            select c.id, c.student_id, s.registration_no, s.first_name || ' ' || s.surname, c.concern, c.self_referred, c.referred_by_name, c.status,
                   c.next_session_at, c.opened_at, (select count(*) from counselling_notes n where n.case_id = c.id)
            from counselling_cases c join students s on s.id = c.student_id""";

    private static CounsellingSummary summary(ResultSet rs) throws SQLException {
        return new CounsellingSummary(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7),
                rs.getString(8), odt(rs.getTimestamp(9)), odt(rs.getTimestamp(10)), rs.getInt(11));
    }

    private Map<String, Object> lock(Long id) {
        return jdbc.queryForList("select * from discipline_cases where id = ? for update", id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Case not found"));
    }

    private void counsellingExists(Long id) {
        Integer n = jdbc.queryForObject("select count(*) from counselling_cases where id = ?", Integer.class, id);
        if (n == null || n == 0) throw new NotFoundException("Case not found");
    }

    private Student studentByRegNo(String regNo) {
        if (regNo == null || regNo.isBlank()) throw new BusinessRuleException("STUDENT_REQUIRED", "Give the student's registration number");
        Long id = jdbc.query("select id from students where lower(registration_no) = lower(?)", (rs, n) -> rs.getLong(1), regNo.trim()).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("STUDENT_NOT_FOUND", "No student has the registration number " + regNo.trim()));
        return students.findById(id).orElseThrow();
    }

    private Long myStudentId() {
        return jdbc.query("select id from students where user_id = ?", (rs, n) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private static String phone(String p) {
        if (p == null || p.isBlank()) return null;
        String d = p.replaceAll("[\\s-]", "");
        if (!d.matches("(\\+?255|0)[67]\\d{8}")) throw new BusinessRuleException("PHONE_INVALID", "Give a mobile number such as 0712 345 678");
        return d;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String oneOf(String v, Set<String> allowed, String what) {
        String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new BusinessRuleException("FIELD_INVALID", "Choose " + what);
        return u;
    }

    private static String required(String s, int max, String what) {
        String t = cut(s, max);
        if (t == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return t;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
