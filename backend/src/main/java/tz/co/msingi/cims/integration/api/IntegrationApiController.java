package tz.co.msingi.cims.integration.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.common.exception.NotFoundException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only API for connected systems, version 1. Field names are stable; new fields may be added. */
@Tag(name = "Integration API v1")
@RestController
@RequestMapping("/api/integration/v1")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationApiController {

    private final JdbcTemplate jdbc;

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    @Operation(summary = "Programmes")
    @GetMapping("/programmes")
    @PreAuthorize("hasAuthority('SCOPE_programmes:read')")
    public List<Map<String, Object>> programmes() {
        return jdbc.queryForList("""
                select p.code, p.nactvet_code as "nactvetCode", p.name, p.award_family as "awardFamily", p.award_level as "awardLevel",
                       p.award_name as "awardName", p.duration_semesters as "durationSemesters", d.code as "departmentCode", p.active
                from programmes p join departments d on d.id = p.department_id order by p.code""");
    }

    @Operation(summary = "Students, newest changes last; filter by programme, status or change time")
    @GetMapping("/students")
    @PreAuthorize("hasAuthority('SCOPE_students:read')")
    public Page<Map<String, Object>> students(@RequestParam(required = false) String programme, @RequestParam(required = false) String status,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedSince,
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        size = Math.max(1, Math.min(size, 500));
        page = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (programme != null) { where.append(" and p.code = ?"); args.add(programme); }
        if (status != null) { where.append(" and s.status = ?"); args.add(status); }
        if (updatedSince != null) { where.append(" and s.updated_at > ?"); args.add(updatedSince); }
        String from = " from students s join programmes p on p.id = s.programme_id left join campuses c on c.id = s.campus_id";
        Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Map<String, Object>> items = jdbc.queryForList(STUDENT_COLUMNS + from + where + " order by s.updated_at, s.id limit ? offset ?", pageArgs.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    @GetMapping("/students/{regNo}")
    @PreAuthorize("hasAuthority('SCOPE_students:read')")
    public Map<String, Object> student(@PathVariable String regNo) {
        List<Map<String, Object>> r = jdbc.queryForList(STUDENT_COLUMNS
                + " from students s join programmes p on p.id = s.programme_id left join campuses c on c.id = s.campus_id where s.registration_no = ?", regNo);
        if (r.isEmpty()) throw new NotFoundException("Student", regNo);
        return r.get(0);
    }

    @Operation(summary = "Published semester results of a student")
    @GetMapping("/students/{regNo}/results")
    @PreAuthorize("hasAuthority('SCOPE_results:read')")
    public List<Map<String, Object>> results(@PathVariable String regNo) {
        student0(regNo);
        return jdbc.queryForList("""
                select ay.code as "academicYear", se.semester_no as "semester", rs.year_of_study as "yearOfStudy", r.gpa, r.cgpa,
                       r.credits_registered as "creditsRegistered", r.credits_passed as "creditsPassed", r.decision, r.classification,
                       rs.published_at as "publishedAt"
                from semester_results r join result_sheets rs on rs.id = r.result_sheet_id join semesters se on se.id = rs.semester_id
                join academic_years ay on ay.id = se.academic_year_id join students s on s.id = r.student_id
                where s.registration_no = ? and rs.status = 'PUBLISHED' order by ay.start_date, se.semester_no""", regNo);
    }

    @Operation(summary = "Fee payments received since a time")
    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_payments:read')")
    public List<Map<String, Object>> payments(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
                                              @RequestParam(defaultValue = "500") int limit) {
        return jdbc.queryForList("""
                select p.receipt_no as "receiptNo", p.control_no as "reference", p.source, p.channel, p.psp_name as "provider", p.amount,
                       p.paid_at as "paidAt", p.status, i.invoice_no as "invoiceNo", i.payer_type as "payerType", i.payer_name as "payerName",
                       i.purpose, st.registration_no as "registrationNo"
                from payments p join invoices i on i.id = p.invoice_id
                left join students st on i.payer_type = 'STUDENT' and st.id = i.payer_id
                where p.paid_at > ? order by p.paid_at limit ?""", since, Math.max(1, Math.min(limit, 2000)));
    }

    private void student0(String regNo) {
        Integer n = jdbc.queryForObject("select count(*) from students where registration_no = ?", Integer.class, regNo);
        if (n == null || n == 0) throw new NotFoundException("Student", regNo);
    }

    private static final String STUDENT_COLUMNS = """
            select s.registration_no as "registrationNo", s.first_name as "firstName", s.middle_name as "middleName", s.surname,
                   s.sex, s.phone, s.email, p.code as "programmeCode", p.name as "programmeName", s.year_of_study as "yearOfStudy",
                   s.status, c.code as "campusCode", s.updated_at as "updatedAt"
            """;
}
