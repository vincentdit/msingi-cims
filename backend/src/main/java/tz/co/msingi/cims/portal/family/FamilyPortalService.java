package tz.co.msingi.cims.portal.family;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos;
import tz.co.msingi.cims.exams.ResultSheetService;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.OtpService;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.teaching.AttendanceService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The family and sponsor portal (FR-STU-05): parents, guardians and sponsors sign in with a code sent to their phone and see the students
 * linked to that phone: the next of kin on the student's record, a person the registry added, or a sponsor's contact. Fees and payments
 * are always shown; results and attendance only where the student shares them (for a sponsor: where the institution allows it).
 * Nothing can be changed from the portal.
 */
@Service
@RequiredArgsConstructor
public class FamilyPortalService {

    static final int CODE_MINUTES = 10;
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_CODES_PER_15_MIN = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Messaging messaging;
    private final TokenService tokens;
    private final OtpService otp;
    private final AuditService audit;
    private final AccountService accounts;
    private final ResultSheetService results;
    private final AttendanceService attendance;
    private final GuardianLinks guardianLinks;

    public record CodeSent(String message, String developmentCode) {
    }

    /** A student the signed-in phone may see, and why. */
    public record LinkedStudent(Long studentId, String registrationNo, String name, String programmeCode, String programmeName, int yearOfStudy,
                                String status, String as, String relation, boolean seesResults, boolean seesAttendance) {
    }

    /** A family link as the student or the registry sees it. */
    public record LinkView(Long id, String phone, String name, String relation, String source, boolean shareResults, boolean shareAttendance,
                           boolean active, OffsetDateTime lastSeenAt) {
    }

    public record Settings(boolean enabled, boolean sponsorsSeeResults) {
    }

    // ------------------------------------------------------------------ sign-in

    /** Sends a code when the phone is linked to a current student; the answer is the same either way, so numbers cannot be probed. */
    @Transactional
    public CodeSent requestCode(String rawPhone) {
        if (!settings().enabled()) throw new BusinessRuleException("FAMILY_PORTAL_OFF", "The institution has not opened the family portal.");
        String phone = normalise(rawPhone);
        String generic = "If this number is registered with the institution, a sign-in code has been sent to it by SMS.";
        syncNextOfKin(phone);
        if (linked(phone).isEmpty()) return new CodeSent(generic, null);
        Integer recent = jdbc.queryForObject("select count(*) from guardian_codes where phone = ? and created_at > now() - interval '15 minutes'",
                Integer.class, phone);
        if (recent != null && recent >= MAX_CODES_PER_15_MIN) {
            throw new BusinessRuleException("TOO_MANY_CODES", "Several codes were sent to this number recently. Wait 15 minutes and try again.");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("update guardian_codes set used_at = now() where phone = ? and used_at is null", phone);
        jdbc.update("insert into guardian_codes (tenant_id, phone, code_hash, expires_at) values (cims_current_tenant(), ?, ?, now() + make_interval(mins => ?))",
                phone, hash(phone, code), CODE_MINUTES);
        messaging.securityCode("FAMILY_CODE", phone, code, Map.of("minutes", String.valueOf(CODE_MINUTES)));
        return new CodeSent(generic, otp.exposeInResponse() ? code : null);
    }

    @Transactional(noRollbackFor = BusinessRuleException.class)
    public TokenService.TokenResponse verify(String rawPhone, String code) {
        String phone = normalise(rawPhone);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code_hash, attempts, expires_at > now() as live from guardian_codes where phone = ? and used_at is null
                order by id desc limit 1""", phone);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("live"))) {
            throw new BusinessRuleException("CODE_EXPIRED", "The code has expired or was not asked for. Ask for a new code.");
        }
        Map<String, Object> r = rows.get(0);
        long id = ((Number) r.get("id")).longValue();
        int attempts = ((Number) r.get("attempts")).intValue() + 1;
        jdbc.update("update guardian_codes set attempts = ? where id = ?", attempts, id);
        if (attempts > MAX_ATTEMPTS) {
            jdbc.update("update guardian_codes set used_at = now() where id = ?", id);
            throw new BusinessRuleException("CODE_EXPIRED", "Too many wrong codes. Ask for a new code.");
        }
        if (!MessageDigest.isEqual(((String) r.get("code_hash")).getBytes(StandardCharsets.UTF_8),
                hash(phone, code == null ? "" : code.trim()).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessRuleException("CODE_INVALID", "The code is incorrect.");
        }
        jdbc.update("update guardian_codes set used_at = now() where id = ?", id);
        List<LinkedStudent> students = linked(phone);
        if (students.isEmpty()) throw new BusinessRuleException("CODE_EXPIRED", "This number is no longer linked to a student.");
        jdbc.update("update guardian_links set last_seen_at = now() where phone = ? and active", phone);
        String name = jdbc.query("select name from guardian_links where phone = ? and active and name is not null order by id limit 1",
                (rs, i) -> rs.getString(1), phone).stream().findFirst().orElse(null);
        audit.recordAs(null, "Family portal " + PhoneNumbers.mask(phone), "FAMILY_SIGN_IN", "GuardianLink", null,
                Map.of("phone", PhoneNumbers.mask(phone), "students", students.size()));
        return tokens.issueFamily(phone, name);
    }

    // ------------------------------------------------------------------ the portal (family token)

    @Transactional(readOnly = true)
    public List<LinkedStudent> myStudents() {
        open();
        return linked(currentPhone());
    }

    @Transactional(readOnly = true)
    public tz.co.msingi.cims.finance.accounts.AccountDtos.Statement fees(Long studentId) {
        allowed(studentId);
        return accounts.forStudent(studentId);
    }

    @Transactional(readOnly = true)
    public ExamDtos.StudentResults results(Long studentId) {
        LinkedStudent s = allowed(studentId);
        if (!s.seesResults()) throw new BusinessRuleException("NOT_SHARED", "The student has not shared results with this number.");
        return results.resultsOf(studentId);
    }

    @Transactional(readOnly = true)
    public tz.co.msingi.cims.teaching.TeachingDtos.MyAttendance attendance(Long studentId) {
        LinkedStudent s = allowed(studentId);
        if (!s.seesAttendance()) throw new BusinessRuleException("NOT_SHARED", "The student has not shared attendance with this number.");
        return attendance.attendanceOf(studentId, null);
    }

    private LinkedStudent allowed(Long studentId) {
        open();
        return linked(currentPhone()).stream().filter(s -> s.studentId().equals(studentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Student", studentId));
    }

    /** Closing the portal also ends the sign-ins already given (they last at most 30 minutes anyway). */
    private void open() {
        if (!settings().enabled()) throw new BusinessRuleException("FAMILY_PORTAL_OFF", "The institution has closed the family portal.");
    }

    private static String currentPhone() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
                && TokenService.TYPE_FAMILY.equals(t.getToken().getClaimAsString(TokenService.CLAIM_USER_TYPE))) {
            return t.getToken().getClaimAsString(TokenService.CLAIM_PHONE);
        }
        throw new BusinessRuleException("NOT_FAMILY", "Sign in to the family portal first.");
    }

    /** The current students this phone may see: as guardian (active link) or as a sponsor's contact (active sponsorship this year). */
    List<LinkedStudent> linked(String phone) {
        Settings settings = settings();
        Map<Long, LinkedStudent> out = new LinkedHashMap<>();
        jdbc.query("""
                select s.id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), p.code, p.name, s.year_of_study, s.status,
                       g.relation, g.share_results, g.share_attendance
                from guardian_links g join students s on s.id = g.student_id join programmes p on p.id = s.programme_id
                where g.phone = ? and g.active and s.status in ('ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED') order by s.registration_no""", rs -> {
            out.putIfAbsent(rs.getLong(1), new LinkedStudent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                    rs.getString(7), "GUARDIAN", rs.getString(8), rs.getBoolean(9), rs.getBoolean(10)));
        }, phone);
        jdbc.query("""
                select s.id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), p.code, p.name, s.year_of_study, s.status,
                       sp.name, sp.contact_phone
                from sponsorships x join sponsors sp on sp.id = x.sponsor_id join students s on s.id = x.student_id join programmes p on p.id = s.programme_id
                join academic_years y on y.id = x.academic_year_id
                where x.status = 'ACTIVE' and sp.active and y.is_current and sp.contact_phone is not null
                  and s.status in ('ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED') order by s.registration_no""", rs -> {
            if (!phone.equals(normaliseOrNull(rs.getString(9)))) return;
            out.putIfAbsent(rs.getLong(1), new LinkedStudent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                    rs.getString(7), "SPONSOR", rs.getString(8), settings.sponsorsSeeResults(), settings.sponsorsSeeResults()));
        });
        return new ArrayList<>(out.values());
    }

    /** The next of kin recorded on current students become family links the first time their phone asks for a code. */
    private void syncNextOfKin(String phone) {
        jdbc.query("""
                select id, next_of_kin_phone, next_of_kin_name, next_of_kin_relation from students
                where next_of_kin_phone is not null and status in ('ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED')
                  and right(regexp_replace(next_of_kin_phone, '[^0-9]', '', 'g'), 9) = right(?, 9)""", rs -> {
            if (!phone.equals(normaliseOrNull(rs.getString(2)))) return;
            jdbc.update("""
                    insert into guardian_links (tenant_id, student_id, phone, name, relation, source, created_by_name)
                    values (cims_current_tenant(), ?, ?, ?, ?, 'NEXT_OF_KIN', 'Student record') on conflict (tenant_id, student_id, phone) do nothing""",
                    rs.getLong(1), phone, rs.getString(3), rs.getString(4));
        }, phone);
    }

    // ------------------------------------------------------------------ the student's and the registry's side

    /** The signed-in student's family links (also the next of kin, who may not have signed in yet). */
    @Transactional
    public List<LinkView> mine() {
        Long studentId = myStudentId();
        guardianLinks.linkNextOfKin(studentId);
        return links(studentId, true);
    }

    @Transactional
    public LinkView share(Long linkId, boolean results, boolean att) {
        Long studentId = myStudentId();
        int n = jdbc.update("update guardian_links set share_results = ?, share_attendance = ? where id = ? and student_id = ?", results, att, linkId, studentId);
        if (n == 0) throw new NotFoundException("Family link", linkId);
        audit.record("FAMILY_SHARING_CHANGED", "GuardianLink", linkId, Map.of("results", results, "attendance", att));
        return links(studentId, true).stream().filter(l -> l.id().equals(linkId)).findFirst().orElseThrow();
    }

    @Transactional
    public List<LinkView> forStudent(Long studentId) {
        guardianLinks.linkNextOfKin(studentId);
        return links(studentId, false);
    }

    @Transactional
    public LinkView add(Long studentId, String rawPhone, String name, String relation) {
        String phone = normalise(rawPhone);
        Integer exists = jdbc.queryForObject("select count(*) from students where id = ?", Integer.class, studentId);
        if (exists == null || exists == 0) throw new NotFoundException("Student", studentId);
        jdbc.update("""
                insert into guardian_links (tenant_id, student_id, phone, name, relation, source, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, 'ADDED', ?)
                on conflict (tenant_id, student_id, phone) do update set active = true, name = excluded.name, relation = excluded.relation""",
                studentId, phone, blank(name), blank(relation), CurrentUser.name());
        audit.record("FAMILY_LINK_ADDED", "Student", studentId, Map.of("phone", PhoneNumbers.mask(phone)));
        return links(studentId, false).stream().filter(l -> phone.equals(l.phone())).findFirst().orElseThrow();
    }

    @Transactional
    public LinkView setActive(Long studentId, Long linkId, boolean active) {
        int n = jdbc.update("update guardian_links set active = ? where id = ? and student_id = ?", active, linkId, studentId);
        if (n == 0) throw new NotFoundException("Family link", linkId);
        audit.record(active ? "FAMILY_LINK_RESTORED" : "FAMILY_LINK_REMOVED", "GuardianLink", linkId, Map.of("studentId", studentId));
        return links(studentId, false).stream().filter(l -> l.id().equals(linkId)).findFirst().orElseThrow();
    }

    private List<LinkView> links(Long studentId, boolean masked) {
        return jdbc.query("""
                select id, phone, name, relation, source, share_results, share_attendance, active, last_seen_at from guardian_links
                where student_id = ? order by active desc, id""",
                (rs, i) -> new LinkView(rs.getLong(1), masked ? PhoneNumbers.mask(rs.getString(2)) : rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getObject(9, OffsetDateTime.class)), studentId);
    }

    private Long myStudentId() {
        return jdbc.query("select id from students where user_id = ?", (rs, i) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Settings settings() {
        return jdbc.query("select family_portal_enabled, sponsors_see_results from finance_settings",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getBoolean(2))).stream().findFirst().orElse(new Settings(true, true));
    }

    @Transactional
    public Settings setSettings(Settings s) {
        int n = jdbc.update("update finance_settings set family_portal_enabled = ?, sponsors_see_results = ?", s.enabled(), s.sponsorsSeeResults());
        if (n == 0) {
            jdbc.update("insert into finance_settings (tenant_id, family_portal_enabled, sponsors_see_results) values (cims_current_tenant(), ?, ?)",
                    s.enabled(), s.sponsorsSeeResults());
        }
        audit.record("FAMILY_PORTAL_SETTINGS", "FinanceSettings", null, Map.of("enabled", s.enabled(), "sponsorsSeeResults", s.sponsorsSeeResults()));
        return settings();
    }

    // ------------------------------------------------------------------ helpers

    private static String normalise(String raw) {
        try {
            return PhoneNumbers.normalise(raw);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("INVALID_PHONE", "Give a Tanzanian mobile number, e.g. 0712 345 678.");
        }
    }

    static String normaliseOrNull(String raw) {
        try {
            return raw == null ? null : PhoneNumbers.normalise(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hash(String phone, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("family|" + phone + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
