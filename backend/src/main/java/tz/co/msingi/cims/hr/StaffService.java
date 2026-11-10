package tz.co.msingi.cims.hr;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.TabularFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Staff records (FR-HR-01..02, FR-HR-04): everyone who works for the institution, with or without a CIMS account, their employment
 * and qualifications (with the certificate, checked by HR), people leaving, contracts about to end, and the staffing figures NACTVET
 * asks for (academic staff by highest qualification and sex, and the rest of the staff by category).
 */
@Service
@RequiredArgsConstructor
public class StaffService {

    static final Set<String> CATEGORIES = Set.of("ACADEMIC", "ADMINISTRATIVE", "TECHNICAL", "SUPPORT");
    static final Set<String> EMPLOYMENT = Set.of("PERMANENT", "CONTRACT", "PART_TIME", "VOLUNTEER");
    static final List<String> QUALIFICATIONS = List.of("NONE", "CERTIFICATE", "DIPLOMA", "ADVANCED_DIPLOMA", "BACHELOR", "POSTGRADUATE_DIPLOMA", "MASTERS", "PHD");
    static final Set<String> LEFT_REASONS = Set.of("RESIGNED", "RETIRED", "CONTRACT_ENDED", "DISMISSED", "TRANSFERRED", "DECEASED", "OTHER");
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record StaffRequest(String staffNo, String title, String fullName, String gender, LocalDate dateOfBirth, String nationality, String nin,
                               String phone, String email, String jobTitle, String category, String employment, Long departmentId, Long campusId,
                               LocalDate firstAppointedOn, LocalDate contractEndsOn, String highestQualification, String specialisation,
                               String tutorRegNo, Long userId) {
    }

    public record StaffSummary(Long id, String staffNo, String title, String fullName, String gender, String jobTitle, String category, String employment,
                               String department, Long departmentId, String campus, String highestQualification, String phone, String status,
                               LocalDate contractEndsOn, boolean hasAccount) {
    }

    public record StaffDetail(Long id, String staffNo, String title, String fullName, String gender, LocalDate dateOfBirth, String nationality, String nin,
                              String phone, String email, String jobTitle, String category, String employment, Long departmentId, String department,
                              Long campusId, String campus, LocalDate firstAppointedOn, LocalDate contractEndsOn, String highestQualification,
                              String specialisation, String tutorRegNo, String status, LocalDate leftOn, String leftReason, Long userId, String username,
                              List<Qualification> qualifications) {
    }

    public record Qualification(Long id, String level, String award, String institution, int year, String fileName, String verifiedByName,
                                OffsetDateTime verifiedAt) {
    }

    public record Account(Long id, String fullName, String username, String jobTitle, Long linkedStaffId) {
    }

    public record Overview(int active, int academic, int female, int contractsEnding, int withoutQualification, List<StaffSummary> endingSoon) {
    }

    public record ImportResult(int added, int skipped, List<String> problems) {
    }

    public record FileContent(String fileName, String contentType, byte[] data) {
    }

    // ------------------------------------------------------------------ records

    @Transactional(readOnly = true)
    public List<StaffSummary> list(String q, String status, String category, Long departmentId) {
        StringBuilder sql = new StringBuilder(SUMMARY + " where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" and m.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and m.category = ?");
            args.add(category.toUpperCase(Locale.ROOT));
        }
        if (departmentId != null) {
            sql.append(" and m.department_id = ?");
            args.add(departmentId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (lower(m.full_name) like ? or lower(m.staff_no) like ? or lower(coalesce(m.job_title, '')) like ?)");
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by m.full_name");
        return jdbc.query(sql.toString(), (rs, n) -> summary(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public StaffDetail get(Long id) {
        return jdbc.query("""
                select m.*, d.name as department, c.name as campus, u.username from staff_members m left join departments d on d.id = m.department_id
                left join campuses c on c.id = m.campus_id left join user_accounts u on u.id = m.user_id where m.id = ?""", (rs, n) -> new StaffDetail(
                rs.getLong("id"), rs.getString("staff_no"), rs.getString("title"), rs.getString("full_name"), rs.getString("gender"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("nationality"), rs.getString("nin"), rs.getString("phone"), rs.getString("email"),
                rs.getString("job_title"), rs.getString("category"), rs.getString("employment"), (Long) rs.getObject("department_id"), rs.getString("department"),
                (Long) rs.getObject("campus_id"), rs.getString("campus"), rs.getObject("first_appointed_on", LocalDate.class),
                rs.getObject("contract_ends_on", LocalDate.class), rs.getString("highest_qualification"), rs.getString("specialisation"),
                rs.getString("tutor_reg_no"), rs.getString("status"), rs.getObject("left_on", LocalDate.class), rs.getString("left_reason"),
                (Long) rs.getObject("user_id"), rs.getString("username"), qualifications(id)), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
    }

    @Transactional
    public StaffDetail save(Long id, StaffRequest r) {
        String staffNo = required(r.staffNo(), 30, "the staff number");
        String name = required(r.fullName(), 200, "the full name");
        String gender = r.gender() == null ? "" : r.gender().toUpperCase(Locale.ROOT);
        if (!Set.of("M", "F").contains(gender)) throw new BusinessRuleException("GENDER_REQUIRED", "Choose male or female");
        String category = oneOf(r.category(), CATEGORIES, "the staff category");
        String employment = oneOf(r.employment(), EMPLOYMENT, "the terms of employment");
        String qual = r.highestQualification() == null || r.highestQualification().isBlank() ? null : oneOf(r.highestQualification(), Set.copyOf(QUALIFICATIONS), "the highest qualification");
        String phone = r.phone() == null || r.phone().isBlank() ? null : PhoneNumbers.normalise(r.phone());
        if ("CONTRACT".equals(employment) && r.contractEndsOn() == null) throw new BusinessRuleException("CONTRACT_END", "Give the date the contract ends");
        if (r.dateOfBirth() != null && r.dateOfBirth().isAfter(LocalDate.now(TZ).minusYears(15))) throw new BusinessRuleException("DOB_INVALID", "Check the date of birth");
        if (r.email() != null && !r.email().isBlank() && !r.email().trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new BusinessRuleException("EMAIL_INVALID", "Check the email address");
        if (taken("select count(*) from staff_members where lower(staff_no) = lower(?) and id <> ?", staffNo, id == null ? -1L : id)) {
            throw new BusinessRuleException("STAFF_NO_TAKEN", "Another staff member has the number " + staffNo);
        }
        if (r.userId() != null) {
            String type = jdbc.query("select user_type from user_accounts where id = ?", (rs, n) -> rs.getString(1), r.userId()).stream().findFirst().orElse(null);
            if (!"STAFF".equals(type)) throw new BusinessRuleException("ACCOUNT_INVALID", "Choose a staff account");
            if (taken("select count(*) from staff_members where user_id = ? and id <> ?", r.userId(), id == null ? -1L : id)) {
                throw new BusinessRuleException("ACCOUNT_LINKED", "That account belongs to another staff record");
            }
        }
        Object[] v = {staffNo, cut(r.title(), 10), name, gender, r.dateOfBirth(), r.nationality() == null || r.nationality().isBlank() ? "Tanzanian" : cut(r.nationality(), 60),
                cut(r.nin(), 25), phone, cut(r.email(), 120), cut(r.jobTitle(), 120), category, employment, r.departmentId(), r.campusId(), r.firstAppointedOn(),
                r.contractEndsOn(), qual, cut(r.specialisation(), 150), cut(r.tutorRegNo(), 40), r.userId()};
        if (id == null) {
            id = jdbc.queryForObject("""
                    insert into staff_members (tenant_id, staff_no, title, full_name, gender, date_of_birth, nationality, nin, phone, email, job_title, category,
                      employment, department_id, campus_id, first_appointed_on, contract_ends_on, highest_qualification, specialisation, tutor_reg_no, user_id)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, v);
            audit.record("STAFF_RECORD_ADDED", "StaffMember", id, Map.of("staffNo", staffNo));
        } else {
            Object[] w = java.util.Arrays.copyOf(v, v.length + 1);
            w[v.length] = id;
            if (jdbc.update("""
                    update staff_members set staff_no = ?, title = ?, full_name = ?, gender = ?, date_of_birth = ?, nationality = ?, nin = ?, phone = ?, email = ?,
                      job_title = ?, category = ?, employment = ?, department_id = ?, campus_id = ?, first_appointed_on = ?, contract_ends_on = ?,
                      highest_qualification = ?, specialisation = ?, tutor_reg_no = ?, user_id = ?, updated_at = now() where id = ?""", w) == 0) {
                throw new NotFoundException("Staff member not found");
            }
            audit.record("STAFF_RECORD_CHANGED", "StaffMember", id, Map.of("staffNo", staffNo));
        }
        return get(id);
    }

    /** Records that the person has left (or, with a null reason, that they are back). Their account is not touched here. */
    @Transactional
    public StaffDetail setLeft(Long id, LocalDate on, String reason) {
        get(id);
        if (reason == null) {
            jdbc.update("update staff_members set status = 'ACTIVE', left_on = null, left_reason = null, updated_at = now() where id = ?", id);
            audit.record("STAFF_RETURNED", "StaffMember", id, Map.of());
            return get(id);
        }
        String why = oneOf(reason, LEFT_REASONS, "why the person left");
        if (on == null) throw new BusinessRuleException("DATE_REQUIRED", "Give the date the person left");
        jdbc.update("update staff_members set status = 'LEFT', left_on = ?, left_reason = ?, updated_at = now() where id = ?", on, why, id);
        jdbc.update("update leave_requests set status = 'CANCELLED', decision_note = 'The person left' where staff_id = ? and status = 'SUBMITTED'", id);
        audit.record("STAFF_LEFT", "StaffMember", id, Map.of("on", on.toString(), "reason", why));
        return get(id);
    }

    /** Staff accounts for linking a record to the person's CIMS sign-in. */
    @Transactional(readOnly = true)
    public List<Account> accounts() {
        return jdbc.query("""
                select u.id, u.full_name, u.username, u.job_title, m.id from user_accounts u left join staff_members m on m.user_id = u.id
                where u.user_type = 'STAFF' and u.status <> 'DISABLED' order by u.full_name""",
                (rs, n) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), (Long) rs.getObject(5)));
    }

    // ------------------------------------------------------------------ qualifications

    @Transactional
    public StaffDetail addQualification(Long staffId, String level, String award, String institution, Integer year, MultipartFile file) {
        get(staffId);
        String lv = oneOf(level, Set.copyOf(QUALIFICATIONS), "the level");
        if (year == null || year < 1950 || year > LocalDate.now(TZ).getYear()) throw new BusinessRuleException("YEAR_INVALID", "Check the year the award was made");
        UploadedFile f = UploadedFile.read(file, 5 * 1024 * 1024, UploadedFile.Allowed.PDF_OR_IMAGE);
        Long id = jdbc.queryForObject("""
                insert into staff_qualifications (tenant_id, staff_id, level, award, institution, year, file_name, content_type, data)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, staffId, lv, required(award, 200, "the award"),
                required(institution, 200, "the awarding institution"), year, f == null ? null : f.fileName(), f == null ? null : f.contentType(), f == null ? null : f.data());
        // the highest qualification follows the records unless HR set a higher one
        jdbc.update("""
                update staff_members set highest_qualification = ?, updated_at = now() where id = ?
                  and (highest_qualification is null or array_position(?::text[], highest_qualification) < array_position(?::text[], ?))""",
                lv, staffId, QUALIFICATIONS.toArray(new String[0]), QUALIFICATIONS.toArray(new String[0]), lv);
        audit.record("STAFF_QUALIFICATION_ADDED", "StaffMember", staffId, Map.of("qualificationId", id, "level", lv, "file", f != null));
        return get(staffId);
    }

    @Transactional
    public StaffDetail verifyQualification(Long qualificationId) {
        Long staffId = staffOf(qualificationId);
        jdbc.update("update staff_qualifications set verified_by_name = ?, verified_at = now() where id = ?", CurrentUser.name(), qualificationId);
        audit.record("STAFF_QUALIFICATION_VERIFIED", "StaffMember", staffId, Map.of("qualificationId", qualificationId));
        return get(staffId);
    }

    @Transactional
    public StaffDetail deleteQualification(Long qualificationId) {
        Long staffId = staffOf(qualificationId);
        jdbc.update("delete from staff_qualifications where id = ?", qualificationId);
        audit.record("STAFF_QUALIFICATION_REMOVED", "StaffMember", staffId, Map.of("qualificationId", qualificationId));
        return get(staffId);
    }

    @Transactional(readOnly = true)
    public FileContent qualificationFile(Long qualificationId) {
        return jdbc.query("select file_name, content_type, data from staff_qualifications where id = ? and data is not null",
                (rs, n) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), qualificationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No certificate is attached"));
    }

    // ------------------------------------------------------------------ overview, import, NACTVET figures

    @Transactional(readOnly = true)
    public Overview overview() {
        LocalDate today = LocalDate.now(TZ);
        Map<String, Object> c = jdbc.queryForMap("""
                select count(*) filter (where status = 'ACTIVE') as active, count(*) filter (where status = 'ACTIVE' and category = 'ACADEMIC') as academic,
                       count(*) filter (where status = 'ACTIVE' and gender = 'F') as female,
                       count(*) filter (where status = 'ACTIVE' and contract_ends_on between ? and ?) as ending,
                       count(*) filter (where status = 'ACTIVE' and category = 'ACADEMIC' and highest_qualification is null) as noqual
                from staff_members""", today, today.plusDays(60));
        List<StaffSummary> ending = jdbc.query(SUMMARY + " where m.status = 'ACTIVE' and m.contract_ends_on <= ? order by m.contract_ends_on",
                (rs, n) -> summary(rs), today.plusDays(60));
        return new Overview(num(c.get("active")), num(c.get("academic")), num(c.get("female")), num(c.get("ending")), num(c.get("noqual")), ending);
    }

    /** Imports staff from CSV or Excel: Staff no, Full name, Gender, Category, Employment, Job title, Department, Phone, Email, ... */
    @Transactional
    public ImportResult importStaff(byte[] data) {
        List<Map<String, String>> rows = TabularFile.read(data);
        int added = 0, skipped = 0;
        List<String> problems = new ArrayList<>();
        Map<String, Long> departments = new LinkedHashMap<>();
        jdbc.query("select id, code, name from departments", rs -> {
            departments.put(rs.getString(2).toLowerCase(Locale.ROOT), rs.getLong(1));
            departments.put(rs.getString(3).toLowerCase(Locale.ROOT), rs.getLong(1));
        });
        for (Map<String, String> row : rows) {
            String line = "Row " + row.get("_row") + ": ";
            try {
                String dept = row.get("department");
                Long deptId = dept == null ? null : departments.get(dept.trim().toLowerCase(Locale.ROOT));
                if (dept != null && deptId == null) problems.add(line + "department " + dept + " not found, left empty");
                String gender = row.get("gender") == null ? null : row.get("gender").trim().substring(0, 1);
                save(null, new StaffRequest(row.get("staffno"), row.get("title"), row.get("fullname"), gender, date(row.get("dateofbirth")), row.get("nationality"),
                        null, row.get("phone"), row.get("email"), row.get("jobtitle"), up(row.get("category")), up(row.get("employment")), deptId, null,
                        date(row.get("firstappointed")), date(row.get("contractends")), up(row.get("highestqualification")), row.get("specialisation"),
                        row.get("tutorregno"), null));
                added++;
            } catch (BusinessRuleException e) {
                skipped++;
                problems.add(line + e.getMessage());
            }
        }
        audit.record("STAFF_IMPORTED", "StaffMember", null, Map.of("added", added, "skipped", skipped));
        return new ImportResult(added, skipped, problems.size() > 200 ? problems.subList(0, 200) : problems);
    }

    /** The staffing figures and list for NACTVET returns and the Governing Board. */
    @Transactional
    public byte[] staffingReturn() {
        List<List<Object>> summary = new ArrayList<>();
        summary.add(List.of("Academic staff by highest qualification"));
        for (String q : QUALIFICATIONS.reversed()) {
            Map<String, Object> c = jdbc.queryForMap("""
                    select count(*) filter (where gender = 'M') as m, count(*) filter (where gender = 'F') as f, count(*) as t from staff_members
                    where status = 'ACTIVE' and category = 'ACADEMIC' and highest_qualification = ?""", q);
            summary.add(List.of(label(q), num(c.get("m")), num(c.get("f")), num(c.get("t"))));
        }
        Map<String, Object> none = jdbc.queryForMap("""
                select count(*) filter (where gender = 'M') as m, count(*) filter (where gender = 'F') as f, count(*) as t from staff_members
                where status = 'ACTIVE' and category = 'ACADEMIC' and highest_qualification is null""");
        if (num(none.get("t")) > 0) summary.add(List.of("Not recorded", num(none.get("m")), num(none.get("f")), num(none.get("t"))));
        summary.add(new ArrayList<>());
        summary.add(List.of("Academic staff by terms of employment"));
        for (String e : List.of("PERMANENT", "CONTRACT", "PART_TIME", "VOLUNTEER")) {
            Map<String, Object> c = jdbc.queryForMap("""
                    select count(*) filter (where gender = 'M') as m, count(*) filter (where gender = 'F') as f, count(*) as t from staff_members
                    where status = 'ACTIVE' and category = 'ACADEMIC' and employment = ?""", e);
            summary.add(List.of(label(e), num(c.get("m")), num(c.get("f")), num(c.get("t"))));
        }
        summary.add(new ArrayList<>());
        summary.add(List.of("All staff by category"));
        for (String cat : List.of("ACADEMIC", "TECHNICAL", "ADMINISTRATIVE", "SUPPORT")) {
            Map<String, Object> c = jdbc.queryForMap("""
                    select count(*) filter (where gender = 'M') as m, count(*) filter (where gender = 'F') as f, count(*) as t from staff_members
                    where status = 'ACTIVE' and category = ?""", cat);
            summary.add(List.of(label(cat), num(c.get("m")), num(c.get("f")), num(c.get("t"))));
        }
        List<List<Object>> list = jdbc.query("""
                select m.staff_no, m.full_name, m.gender, m.nationality, m.job_title, d.name, m.highest_qualification, m.specialisation, m.employment,
                       m.first_appointed_on, m.contract_ends_on, m.tutor_reg_no
                from staff_members m left join departments d on d.id = m.department_id where m.status = 'ACTIVE' and m.category = 'ACADEMIC'
                order by d.name nulls last, m.full_name""", (rs, n) -> {
            List<Object> r = new ArrayList<>();
            r.add(rs.getString(1));
            r.add(rs.getString(2));
            r.add("M".equals(rs.getString(3)) ? "Male" : "Female");
            r.add(rs.getString(4));
            r.add(rs.getString(5));
            r.add(rs.getString(6));
            r.add(rs.getString(7) == null ? null : label(rs.getString(7)));
            r.add(rs.getString(8));
            r.add(label(rs.getString(9)));
            r.add(rs.getObject(10, LocalDate.class));
            r.add(rs.getObject(11, LocalDate.class));
            r.add(rs.getString(12));
            return r;
        });
        List<List<Object>> others = jdbc.query("""
                select m.staff_no, m.full_name, m.gender, m.category, m.job_title, d.name, m.highest_qualification, m.employment
                from staff_members m left join departments d on d.id = m.department_id where m.status = 'ACTIVE' and m.category <> 'ACADEMIC'
                order by m.category, m.full_name""", (rs, n) -> {
            List<Object> r = new ArrayList<>();
            r.add(rs.getString(1));
            r.add(rs.getString(2));
            r.add("M".equals(rs.getString(3)) ? "Male" : "Female");
            r.add(label(rs.getString(4)));
            r.add(rs.getString(5));
            r.add(rs.getString(6));
            r.add(rs.getString(7) == null ? null : label(rs.getString(7)));
            r.add(label(rs.getString(8)));
            return r;
        });
        audit.record("STAFFING_RETURN_DOWNLOADED", "StaffMember", null, Map.of("academic", list.size(), "others", others.size()));
        return new SimpleXlsx()
                .sheet("Summary", List.of("", "Male", "Female", "Total"), summary, 40, 10, 10, 10)
                .sheet("Academic staff", List.of("Staff no", "Name", "Sex", "Nationality", "Job title", "Department", "Highest qualification", "Specialisation",
                        "Employment", "First appointed", "Contract ends", "Tutor registration"), list, 12, 30, 8, 14, 24, 26, 20, 26, 14, 14, 14, 18)
                .sheet("Other staff", List.of("Staff no", "Name", "Sex", "Category", "Job title", "Department", "Highest qualification", "Employment"),
                        others, 12, 30, 8, 16, 24, 26, 20, 14)
                .bytes();
    }

    /** The staff record of the signed-in person, if HR has linked one. */
    @Transactional(readOnly = true)
    public Long myStaffId() {
        return jdbc.query("select id from staff_members where user_id = ? and status = 'ACTIVE'", (rs, n) -> rs.getLong(1), CurrentUser.requireId()).stream()
                .findFirst().orElseThrow(() -> new BusinessRuleException("NO_STAFF_RECORD",
                        "Human Resources has not linked a staff record to your account yet. Ask HR to link it."));
    }

    // ------------------------------------------------------------------ helpers

    private static final String SUMMARY = """
            select m.id, m.staff_no, m.title, m.full_name, m.gender, m.job_title, m.category, m.employment, d.name as department, m.department_id,
                   c.name as campus, m.highest_qualification, m.phone, m.status, m.contract_ends_on, m.user_id
            from staff_members m left join departments d on d.id = m.department_id left join campuses c on c.id = m.campus_id""";

    private static StaffSummary summary(ResultSet rs) throws SQLException {
        return new StaffSummary(rs.getLong("id"), rs.getString("staff_no"), rs.getString("title"), rs.getString("full_name"), rs.getString("gender"),
                rs.getString("job_title"), rs.getString("category"), rs.getString("employment"), rs.getString("department"), (Long) rs.getObject("department_id"),
                rs.getString("campus"), rs.getString("highest_qualification"), rs.getString("phone"), rs.getString("status"),
                rs.getObject("contract_ends_on", LocalDate.class), rs.getObject("user_id") != null);
    }

    private List<Qualification> qualifications(Long staffId) {
        return jdbc.query("""
                select id, level, award, institution, year, file_name, verified_by_name, verified_at from staff_qualifications where staff_id = ?
                order by year desc""", (rs, n) -> new Qualification(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), odt(rs.getTimestamp(8))), staffId);
    }

    private Long staffOf(Long qualificationId) {
        return jdbc.query("select staff_id from staff_qualifications where id = ?", (rs, n) -> rs.getLong(1), qualificationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Qualification not found"));
    }

    private boolean taken(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }

    static String label(String code) {
        if (code == null) return null;
        return switch (code) {
            case "PHD" -> "PhD";
            case "PART_TIME" -> "Part-time";
            default -> code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static String up(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new BusinessRuleException("DATE_INVALID", "Write dates as yyyy-mm-dd (" + s + ")");
        }
    }

    private static String oneOf(String v, Set<String> allowed, String what) {
        String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new BusinessRuleException("FIELD_INVALID", "Choose " + what);
        return u;
    }

    private static String required(String s, int max, String what) {
        if (s == null || s.isBlank()) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return cut(s, max);
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
