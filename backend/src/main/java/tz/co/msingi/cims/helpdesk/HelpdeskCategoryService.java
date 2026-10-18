package tz.co.msingi.cims.helpdesk;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.Role;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What people can ask the institution about. Each category names the office (a staff role) that answers it, who may choose it
 * (applicants, students, staff, people without an account) and how quickly it must be answered and resolved. A first set is
 * created for each institution the first time the helpdesk is used; the administrator changes it under Helpdesk › Settings.
 */
@Service
@RequiredArgsConstructor
public class HelpdeskCategoryService {

    /** Offices a category can be routed to: staff roles that answer people. */
    public static final Set<String> OFFICES = Set.of(Role.INSTITUTION_ADMIN.name(), Role.ADMISSIONS_OFFICER.name(), Role.REGISTRAR.name(),
            Role.BURSAR.name(), Role.ACCOUNTANT.name(), Role.EXAMINATIONS_OFFICER.name(), Role.HOSTEL_WARDEN.name(), Role.IPT_COORDINATOR.name(),
            Role.CLEARANCE_OFFICER.name(), Role.SPONSOR_OFFICER.name(), Role.DEPUTY_PRINCIPAL_ACADEMIC.name());

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record CategoryRequest(@NotBlank @Size(max = 100) String name, @Size(max = 300) String description, @NotBlank String officeRole,
                                  boolean forApplicants, boolean forStudents, boolean forStaff, boolean forGuests,
                                  @Min(1) @Max(720) int responseHours, @Min(1) @Max(2160) int resolutionHours, boolean active, int sortOrder) {
    }

    public record CategoryView(Long id, String name, String description, String officeRole, boolean forApplicants, boolean forStudents,
                               boolean forStaff, boolean forGuests, int responseHours, int resolutionHours, boolean active, int sortOrder,
                               long openTickets) {
    }

    /** The categories offered to one kind of requester (APPLICANT, STUDENT, STAFF or GUEST), active ones only. */
    @Transactional
    public List<CategoryView> forAudience(String requesterType) {
        ensureDefaults();
        String column = switch (requesterType) {
            case "APPLICANT" -> "for_applicants";
            case "STUDENT" -> "for_students";
            case "STAFF" -> "for_staff";
            default -> "for_guests";
        };
        return jdbc.query(SELECT + " where c.active and c." + column + " order by c.sort_order, c.name", MAPPER);
    }

    @Transactional
    public List<CategoryView> all() {
        ensureDefaults();
        return jdbc.query(SELECT + " order by c.sort_order, c.name", MAPPER);
    }

    @Transactional(readOnly = true)
    public CategoryView get(Long id) {
        List<CategoryView> r = jdbc.query(SELECT + " where c.id = ?", MAPPER, id);
        if (r.isEmpty()) throw new NotFoundException("Helpdesk category", id);
        return r.get(0);
    }

    @Transactional
    public CategoryView create(CategoryRequest r) {
        validate(r, null);
        Long id = jdbc.queryForObject("""
                insert into helpdesk_categories (tenant_id, name, description, office_role, for_applicants, for_students, for_staff, for_guests,
                  response_hours, resolution_hours, active, sort_order)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                r.name().trim(), blank(r.description()), r.officeRole(), r.forApplicants(), r.forStudents(), r.forStaff(), r.forGuests(),
                r.responseHours(), r.resolutionHours(), r.active(), r.sortOrder());
        audit.record("HELPDESK_CATEGORY_ADDED", "HelpdeskCategory", id, Map.of("name", r.name().trim(), "office", r.officeRole()));
        return get(id);
    }

    @Transactional
    public CategoryView update(Long id, CategoryRequest r) {
        CategoryView before = get(id);
        validate(r, id);
        jdbc.update("""
                update helpdesk_categories set name = ?, description = ?, office_role = ?, for_applicants = ?, for_students = ?, for_staff = ?,
                  for_guests = ?, response_hours = ?, resolution_hours = ?, active = ?, sort_order = ?, updated_at = now() where id = ?""",
                r.name().trim(), blank(r.description()), r.officeRole(), r.forApplicants(), r.forStudents(), r.forStaff(), r.forGuests(),
                r.responseHours(), r.resolutionHours(), r.active(), r.sortOrder(), id);
        if (!before.officeRole().equals(r.officeRole())) {
            // open tickets follow their category to the new office; one assigned to someone outside it waits for the new office
            jdbc.update("update helpdesk_tickets set office_role = ?, updated_at = now() where category_id = ? and status not in ('RESOLVED', 'CLOSED')",
                    r.officeRole(), id);
        }
        audit.record("HELPDESK_CATEGORY_UPDATED", "HelpdeskCategory", id, Map.of("name", r.name().trim(), "office", r.officeRole(),
                "officeBefore", before.officeRole(), "responseHours", r.responseHours(), "resolutionHours", r.resolutionHours(), "active", r.active()));
        return get(id);
    }

    private void validate(CategoryRequest r, Long id) {
        if (!OFFICES.contains(r.officeRole())) throw new BusinessRuleException("INVALID_OFFICE", "Choose the office that answers this category.");
        if (r.resolutionHours() < r.responseHours()) {
            throw new BusinessRuleException("INVALID_TIMES", "The time to resolve cannot be shorter than the time to first reply.");
        }
        if (!(r.forApplicants() || r.forStudents() || r.forStaff() || r.forGuests())) {
            throw new BusinessRuleException("NO_AUDIENCE", "Choose who can use this category.");
        }
        Integer dup = jdbc.queryForObject("select count(*) from helpdesk_categories where lower(name) = lower(?) and id <> coalesce(?, -1)",
                Integer.class, r.name().trim(), id);
        if (dup != null && dup > 0) throw new BusinessRuleException("DUPLICATE_NAME", "There is already a category called " + r.name().trim() + ".");
    }

    /** The first set of categories, made once per institution. */
    @Transactional
    public void ensureDefaults() {
        Integer n = jdbc.queryForObject("select count(*) from helpdesk_categories", Integer.class);
        if (n != null && n > 0) return;
        // two first visits at once must not both create the set
        jdbc.query("select pg_advisory_xact_lock(56001, cims_current_tenant()::int)", rs -> null);
        n = jdbc.queryForObject("select count(*) from helpdesk_categories", Integer.class);
        if (n != null && n > 0) return;
        // name, description, office, applicants, students, staff, guests, response h, resolution h
        Object[][] rows = {
                {"Applying for admission", "The application, entry requirements, choices, documents and admission letters", "ADMISSIONS_OFFICER", true, false, false, true, 8, 72},
                {"Payments and control numbers", "Application fee, control numbers or payment references, and payments not showing", "BURSAR", true, true, false, true, 8, 48},
                {"Fees and invoices", "Fee statements, instalments, sponsors, refunds and receipts", "BURSAR", false, true, false, false, 8, 72},
                {"Registration and student records", "Semester registration, personal details, ID cards, transfers and status changes", "REGISTRAR", false, true, false, false, 8, 72},
                {"Results and examinations", "Results, examination eligibility, remarks, appeals and transcripts", "EXAMINATIONS_OFFICER", false, true, false, false, 8, 120},
                {"Hostel and accommodation", "Hostel applications, rooms and repairs", "HOSTEL_WARDEN", false, true, false, false, 8, 72},
                {"Signing in and my account", "Passwords, codes that do not arrive, locked accounts and phone or email changes", "INSTITUTION_ADMIN", true, true, false, true, 4, 24},
                {"ICT and system support", "Problems with CIMS, accounts, roles, devices and network for staff", "INSTITUTION_ADMIN", false, false, true, false, 4, 48},
                {"Something else", "Anything not listed above", "REGISTRAR", true, true, true, true, 8, 72},
        };
        int order = 10;
        for (Object[] r : rows) {
            jdbc.update("""
                    insert into helpdesk_categories (tenant_id, name, description, office_role, for_applicants, for_students, for_staff, for_guests,
                      response_hours, resolution_hours, sort_order) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], order);
            order += 10;
        }
        // a few starter answers, so the help pages are useful from the first day; the offices reword them under Helpdesk › Answers
        Object[][] answers = {
                {"Applying for admission", "The portal says my index number is not found",
                        "Check that you typed the index number exactly as on your NECTA results slip, for example S0123/0045, and chose the year you sat the examination. "
                                + "Private candidates' numbers start with P. If it is still not found, ask us for help and attach a photo of your results slip.", true, false, true},
                {"Signing in and my account", "I did not receive the SMS code",
                        "Wait two minutes, check that your phone has network and that the number you entered is yours, then use Send a new code. "
                                + "If you entered a wrong number, start the sign-up again with the right one. Still no code? Ask us for help with your mobile number.", true, true, true},
                {"Signing in and my account", "I forgot my password",
                        "On the sign-in page choose Forgot password, enter your mobile number and the code we send, then choose a new password. "
                                + "Students sign in with their registration number or mobile number.", true, true, true},
                {"Payments and control numbers", "I paid but the payment does not show",
                        "Payments usually show within minutes. Make sure you paid with the control number or payment reference shown in the portal, not another one. "
                                + "If it has not shown after one working day, ask us for help and attach the payment receipt or SMS from your bank or mobile money.", true, true, true},
        };
        for (Object[] a : answers) {
            jdbc.update("""
                    insert into helpdesk_articles (tenant_id, category_id, title, body, for_applicants, for_students, for_public, published, updated_by_name)
                    select cims_current_tenant(), id, ?, ?, ?, ?, ?, true, 'CIMS' from helpdesk_categories where name = ?""",
                    a[1], a[2], a[3], a[4], a[5], a[0]);
        }
    }

    private static final String SELECT = """
            select c.id, c.name, c.description, c.office_role, c.for_applicants, c.for_students, c.for_staff, c.for_guests, c.response_hours,
                   c.resolution_hours, c.active, c.sort_order,
                   (select count(*) from helpdesk_tickets t where t.category_id = c.id and t.status not in ('RESOLVED', 'CLOSED'))
            from helpdesk_categories c""";

    private static final RowMapper<CategoryView> MAPPER = (rs, i) -> new CategoryView(rs.getLong(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9), rs.getInt(10),
            rs.getBoolean(11), rs.getInt(12), rs.getLong(13));

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
