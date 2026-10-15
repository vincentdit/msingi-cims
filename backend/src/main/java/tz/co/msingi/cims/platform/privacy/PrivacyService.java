package tz.co.msingi.cims.platform.privacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data subject rights under the Personal Data Protection Act, 2022 (BR-38 / FR-ADM-S06 / NFR-PRV-01): a person can download
 * what CIMS holds about them, and ask for a correction, erasure or to object; the institution answers within the time limit.
 * Erasure applies to applicants who never became students; academic and financial records of students are kept as the
 * law requires, so such a request is answered with what was kept and why.
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    static final int RESPONSE_DAYS = 30;
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final tz.co.msingi.cims.platform.security.SessionGuard sessions;

    public record RequestForm(@NotBlank @Pattern(regexp = "ACCESS|CORRECTION|ERASURE|OBJECTION") String kind,
                              @NotBlank @Size(max = 2000) String details) {
    }

    public record Decision(@NotBlank @Pattern(regexp = "COMPLETED|REJECTED") String outcome, @NotBlank @Size(max = 2000) String response,
                           boolean erase) {
    }

    public record RequestView(Long id, String requestNo, Long userId, String subjectName, String userType, String kind, String details,
                              String status, LocalDate dueOn, boolean overdue, String response, String handledByName,
                              OffsetDateTime handledAt, OffsetDateTime createdAt, boolean erasable) {
    }

    public record RetentionPreview(int applicantsToAnonymise, int messagesToClear, int emailsToClear, int applicantMonths,
                                   int messageMonths, boolean enabled, List<Map<String, Object>> lastRuns) {
    }

    private final SecuritySettingsService settings;

    // ---------------------------------------------------------------- the person

    @Transactional
    public RequestView submit(RequestForm f) {
        Long uid = CurrentUser.requireId();
        Integer open = jdbc.queryForObject("select count(*) from data_subject_requests where user_id = ? and kind = ? and status = 'OPEN'",
                Integer.class, uid, f.kind());
        if (open != null && open > 0) {
            throw new BusinessRuleException("DSR_OPEN", "You already have an open request of this kind.");
        }
        LocalDate today = LocalDate.now(TZ);
        Integer seq = jdbc.queryForObject("select count(*) + 1 from data_subject_requests where request_no like ?", Integer.class,
                "DSR-" + today.getYear() + "-%");
        String no = "DSR-%d-%04d".formatted(today.getYear(), seq == null ? 1 : seq);
        String name = jdbc.queryForObject("select full_name from user_accounts where id = ?", String.class, uid);
        Long id = jdbc.queryForObject("""
                insert into data_subject_requests (tenant_id, request_no, user_id, subject_name, kind, details, due_on)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                no, uid, name, f.kind(), f.details().trim(), today.plusDays(RESPONSE_DAYS));
        audit.record("DSR_SUBMITTED", "DataSubjectRequest", id, Map.of("kind", f.kind(), "requestNo", no));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<RequestView> mine() {
        return query(" where r.user_id = ? order by r.created_at desc", CurrentUser.requireId());
    }

    /** Everything CIMS holds about the signed-in person, as one document (the right of access, served at once). */
    @Transactional
    public Map<String, Object> export() {
        Long uid = CurrentUser.requireId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", OffsetDateTime.now(TZ).toString());
        out.put("account", one("select username, full_name, email, phone, user_type, status, email_verified_at, created_at from user_accounts where id = ?", uid));
        Map<String, Object> applicant = one("""
                select id, first_name, middle_name, surname, sex, date_of_birth, nationality, phone, email, region, district, postal_address,
                       disability, next_of_kin_name, next_of_kin_phone, next_of_kin_relation, privacy_consent_at, privacy_notice_version
                from applicants where user_id = ?""", uid);
        if (applicant != null) {
            Object aid = applicant.remove("id");
            out.put("applicant", applicant);
            out.put("qualifications", jdbc.queryForList("""
                    select type, index_number, exam_year, centre_name, division, points, source, verified from applicant_qualifications
                    where applicant_id = ?""", aid));
            out.put("applications", jdbc.queryForList("""
                    select a.reference_no, w.name as window, a.status, a.submitted_at from applications a
                    join admission_windows w on w.id = a.window_id where a.applicant_id = ? order by a.created_at""", aid));
        }
        Map<String, Object> student = one("""
                select s.id, s.registration_no, s.first_name, s.middle_name, s.surname, s.sex, s.date_of_birth, s.nationality, s.phone, s.email,
                       s.region, s.district, s.postal_address, s.next_of_kin_name, s.next_of_kin_phone, p.code as programme, s.year_of_study,
                       s.status, s.admitted_at
                from students s join programmes p on p.id = s.programme_id where s.user_id = ?""", uid);
        Long sid = null;
        if (student != null) {
            sid = ((Number) student.remove("id")).longValue();
            out.put("student", student);
        }
        Long aid = applicant == null ? null : jdbc.queryForObject("select id from applicants where user_id = ?", Long.class, uid);
        out.put("invoices", jdbc.queryForList("""
                select invoice_no, purpose, description, total_amount, paid_amount, status, created_at from invoices
                where (payer_type = 'APPLICANT' and payer_id = ?) or (payer_type = 'STUDENT' and payer_id = ?) order by created_at""",
                aid == null ? -1L : aid, sid == null ? -1L : sid));
        String phone = jdbc.queryForObject("select phone from user_accounts where id = ?", String.class, uid);
        out.put("textMessages", phone == null ? List.of() : jdbc.queryForList(
                "select body, status, queued_at from messages where phone = ? order by queued_at desc limit 200", phone));
        out.put("emails", jdbc.queryForList("""
                select subject, status, queued_at from email_messages where lower(to_address) = lower(coalesce((select email from user_accounts where id = ?), '-'))
                order by queued_at desc limit 200""", uid));
        out.put("requests", mine());
        audit.record("DSR_EXPORT", "UserAccount", uid, Map.of());
        return out;
    }

    // ---------------------------------------------------------------- the institution

    @Transactional(readOnly = true)
    public List<RequestView> list(String status) {
        return status == null ? query(" order by r.status = 'OPEN' desc, r.due_on, r.id") : query(" where r.status = ? order by r.due_on, r.id", status);
    }

    @Transactional
    public RequestView decide(Long id, Decision d) {
        RequestView r = get(id);
        if (!"OPEN".equals(r.status())) throw new BusinessRuleException("DSR_CLOSED", "This request has already been answered.");
        if (r.userId().equals(CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("DSR_OWN", "Someone else must answer your own request.");
        }
        if (d.erase()) {
            if (!"ERASURE".equals(r.kind()) || !"COMPLETED".equals(d.outcome())) {
                throw new BusinessRuleException("DSR_ERASE", "Only an erasure request that is being completed can erase a record.");
            }
            if (!r.erasable()) {
                throw new BusinessRuleException("DSR_NOT_ERASABLE",
                        "This person is or was a student; their academic and financial records must be kept. Answer with what is kept and why.");
            }
            anonymiseApplicant(r.userId());
        }
        jdbc.update("""
                update data_subject_requests set status = ?, response = ?, handled_by_name = ?, handled_at = now(), updated_at = now(),
                  version = version + 1 where id = ?""", d.outcome(), d.response().trim(), CurrentUser.name(), id);
        audit.record("DSR_" + d.outcome(), "DataSubjectRequest", id, Map.of("kind", r.kind(), "erased", d.erase()));
        return get(id);
    }

    // ---------------------------------------------------------------- retention

    @Transactional(readOnly = true)
    public RetentionPreview preview() {
        var s = settings.get();
        return new RetentionPreview(count(APPLICANTS_DUE, s.retentionApplicantMonths()), count(MESSAGES_DUE, s.retentionMessageMonths()),
                count(EMAILS_DUE, s.retentionMessageMonths()), s.retentionApplicantMonths(), s.retentionMessageMonths(), s.retentionEnabled(),
                jdbc.queryForList("select ran_at, ran_by, applicants_anonymised, messages_cleared, emails_cleared from retention_runs order by ran_at desc limit 10"));
    }

    /** Anonymise applicants who never became students and clear old messages, past the institution's retention periods. */
    @Transactional
    public Map<String, Integer> runRetention(String by) {
        var s = settings.get();
        List<Long> users = jdbc.queryForList("select a.user_id" + APPLICANTS_DUE.substring(APPLICANTS_DUE.indexOf(" from ")), Long.class,
                s.retentionApplicantMonths());
        users.forEach(this::anonymiseApplicant);
        int messages = jdbc.update("update messages set body = '[cleared under the retention policy]', phone = '-' where id in (select m.id"
                + MESSAGES_DUE.substring(MESSAGES_DUE.indexOf(" from ")) + ")", s.retentionMessageMonths());
        int emails = jdbc.update("update email_messages set body = '[cleared under the retention policy]', attachment = null, to_address = '-' where id in (select e.id"
                + EMAILS_DUE.substring(EMAILS_DUE.indexOf(" from ")) + ")", s.retentionMessageMonths());
        jdbc.update("insert into retention_runs (tenant_id, ran_by, applicants_anonymised, messages_cleared, emails_cleared) values (cims_current_tenant(), ?, ?, ?, ?)",
                by, users.size(), messages, emails);
        Map<String, Integer> r = Map.of("applicantsAnonymised", users.size(), "messagesCleared", messages, "emailsCleared", emails);
        audit.record("RETENTION_RUN", "RetentionRun", null, Map.copyOf(r));
        return r;
    }

    /** Remove what identifies an applicant who never became a student. Invoice and payment amounts stay for the accounts, without the name. */
    void anonymiseApplicant(Long userId) {
        Long aid = jdbc.queryForList("select id from applicants where user_id = ?", Long.class, userId).stream().findFirst().orElse(null);
        String phone = jdbc.queryForObject("select phone from user_accounts where id = ?", String.class, userId);
        if (aid != null) {
            jdbc.update("""
                    update applicants set first_name = 'Erased', middle_name = null, surname = 'Applicant ' || id, sex = null, date_of_birth = null,
                      nationality = null, phone = 'X' || id, email = null, region = null, district = null, postal_address = null, disability = null,
                      next_of_kin_name = null, next_of_kin_phone = null, next_of_kin_relation = null, updated_at = now() where id = ?""", aid);
            jdbc.update("update applicant_qualifications set candidate_name = null, index_number = 'X' || id, centre_name = null where applicant_id = ?", aid);
            jdbc.update("update invoices set payer_name = 'Erased applicant', payer_phone = null, payer_email = null where payer_type = 'APPLICANT' and payer_id = ?", aid);
        }
        jdbc.update("""
                update user_accounts set username = 'erased-' || id, full_name = 'Erased applicant', email = null, phone = null, status = 'DISABLED',
                  mfa_secret = null, mfa_pending_secret = null, mfa_recovery_codes = null, anonymised_at = now(), sessions_valid_after = now(),
                  updated_at = now() where id = ?""", userId);
        jdbc.update("update data_subject_requests set subject_name = 'Erased applicant' where user_id = ?", userId);
        sessions.evict(tz.co.msingi.cims.platform.tenancy.TenantContext.requireTenantId(), userId);
        if (phone != null) {
            jdbc.update("update messages set phone = '-', body = '[erased]' where phone = ?", phone);
        }
    }

    // ----------------------------------------------------------------

    private static final String APPLICANTS_DUE = """
            select count(*) from applicants a join user_accounts u on u.id = a.user_id
            where u.anonymised_at is null and u.user_type = 'APPLICANT'
              and not exists (select 1 from students s where s.applicant_id = a.id or s.user_id = a.user_id)
              and greatest(a.updated_at, coalesce((select max(x.updated_at) from applications x where x.applicant_id = a.id), a.updated_at))
                  < now() - make_interval(months => ?)""";
    private static final String MESSAGES_DUE = """
            select count(*) from messages m where m.phone <> '-' and m.queued_at < now() - make_interval(months => ?)""";
    private static final String EMAILS_DUE = """
            select count(*) from email_messages e where e.to_address <> '-' and e.queued_at < now() - make_interval(months => ?)""";

    private int count(String sql, int months) {
        Integer n = jdbc.queryForObject(sql, Integer.class, months);
        return n == null ? 0 : n;
    }

    private Map<String, Object> one(String sql, Object arg) {
        List<Map<String, Object>> r = jdbc.queryForList(sql, arg);
        return r.isEmpty() ? null : new LinkedHashMap<>(r.get(0));
    }

    private RequestView get(Long id) {
        return query(" where r.id = ?", id).stream().findFirst().orElseThrow(() -> new NotFoundException("DataSubjectRequest", id));
    }

    private List<RequestView> query(String tail, Object... args) {
        LocalDate today = LocalDate.now(TZ);
        return jdbc.query("""
                select r.id, r.request_no, r.user_id, r.subject_name, u.user_type, r.kind, r.details, r.status, r.due_on, r.response,
                       r.handled_by_name, r.handled_at, r.created_at,
                       not exists (select 1 from students s where s.user_id = r.user_id
                                   or s.applicant_id = (select a.id from applicants a where a.user_id = r.user_id)) and u.user_type = 'APPLICANT'
                       and u.anonymised_at is null as erasable
                from data_subject_requests r join user_accounts u on u.id = r.user_id""" + tail,
                (rs, i) -> {
                    LocalDate due = rs.getObject(9, LocalDate.class);
                    String status = rs.getString(8);
                    return new RequestView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), status, due, "OPEN".equals(status) && due.isBefore(today), rs.getString(10), rs.getString(11),
                            rs.getObject(12, OffsetDateTime.class), rs.getObject(13, OffsetDateTime.class), rs.getBoolean(14));
                }, args);
    }
}
