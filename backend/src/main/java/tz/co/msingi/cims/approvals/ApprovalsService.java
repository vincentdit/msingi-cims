package tz.co.msingi.cims.approvals;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything waiting for the signed-in person's decision, in one list: selection rounds and curricula for the Academic
 * Committee, result sheets for the committee and the Governing Board, examination exemptions, result amendments and
 * graduand lists, and module results for the Head of Department's moderation and the Department Board. Each item links
 * to the page where the decision is made, where the usual checks apply. Items a person put forward themselves are shown
 * but cannot be decided by them (segregation of duties).
 */
@Service
public class ApprovalsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final UserAccountRepository users;

    public ApprovalsService(NamedParameterJdbcTemplate jdbc, UserAccountRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public enum Kind {
        SELECTION("Selection", "Academic Committee"),
        CURRICULUM("Curriculum", "Academic Committee"),
        RESULTS_RECOMMEND("Class results", "Academic Committee"),
        RESULTS_CONFIRM("Class results", "Governing Board"),
        EXEMPTION("Examination exemption", "Academic Committee"),
        AMENDMENT("Result amendment", "Administrator"),
        GRADUATION("Graduand list", "Governing Board"),
        MODULE_MODERATION("Module results", "Head of Department"),
        MODULE_BOARD("Module results", "Department Board"),
        RESIT_MODERATION("Resit marks", "Head of Department"),
        COMPETENCE_VERIFY("Competence outcomes (NVA)", "Head of Department"),
        STATUS_RECOMMEND("Student status change", "Head of Department"),
        STATUS_DECISION("Student status change", "Deputy Principal (Academic)"),
        TRANSFER_RECOMMEND("Programme transfer", "Head of Department"),
        TRANSFER_DECISION("Transfer", "Deputy Principal (Academic)"),
        APPEAL_ASSIGN("Remark or appeal", "Examinations Office"),
        APPEAL_FORWARD("Remark or appeal", "Examinations Office"),
        FIN_ADJUSTMENT("Waiver or refund", "Bursar"),
        FIN_ADJUSTMENT_SECOND("Waiver or refund", "Principal");

        public final String label;
        public final String body;

        Kind(String label, String body) {
            this.label = label;
            this.body = body;
        }
    }

    public record Item(String kind, String label, String body, Long id, String title, String detail, String submittedBy,
                       OffsetDateTime submittedAt, String link, boolean canAct, String note) {
    }

    public record Inbox(List<Item> items, long total, long actionable) {
    }

    @Transactional(readOnly = true)
    public Inbox inbox() {
        Set<Role> roles = roles();
        Long me = CurrentUser.id().orElse(-1L);
        boolean committee = roles.contains(Role.INSTITUTION_ADMIN) || roles.contains(Role.DEPUTY_PRINCIPAL_ACADEMIC);
        boolean admin = roles.contains(Role.INSTITUTION_ADMIN);
        boolean examsOffice = admin || roles.contains(Role.EXAMINATIONS_OFFICER);
        Departments hod = headedDepartments(me, roles);
        MapSqlParameterSource p = new MapSqlParameterSource("me", me);
        List<Item> out = new ArrayList<>();

        if (committee) {
            jdbc.query("""
                    select r.id, r.window_id, r.round_no, w.name, r.proposed_by, r.proposed_by_name, r.proposed_at,
                           (select count(*) from applications a where a.window_id = r.window_id and a.selected_window_programme_id is not null) as chosen
                    from selection_rounds r join admission_windows w on w.id = r.window_id where r.status = 'PROPOSED'""", p, rs -> {
                boolean own = Objects.equals(rs.getLong("proposed_by"), me);
                out.add(item(Kind.SELECTION, rs.getLong("id"), w(rs.getString("name")) + " · round " + rs.getInt("round_no"),
                        "Selection proposed for approval", rs.getString("proposed_by_name"), rs.getObject("proposed_at", OffsetDateTime.class),
                        "/staff/windows/" + rs.getLong("window_id") + "/selection", !own, own ? "You proposed this round; someone else approves it." : null));
            });
            jdbc.query("""
                    select c.id, c.curriculum_version, c.created_by, c.created_by_name, c.created_at, p.code, p.name,
                           (select count(*) from curriculum_modules m where m.curriculum_id = c.id) as modules
                    from curricula c join programmes p on p.id = c.programme_id where c.status = 'DRAFT'""", p, rs -> {
                boolean own = Objects.equals(rs.getObject("created_by") == null ? null : rs.getLong("created_by"), me);
                long modules = rs.getLong("modules");
                out.add(item(Kind.CURRICULUM, rs.getLong("id"), rs.getString("code") + " · " + rs.getString("name"),
                        "Draft version " + rs.getInt("curriculum_version") + ", " + modules + (modules == 1 ? " module" : " modules"),
                        rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class), "/staff/curricula/" + rs.getLong("id"),
                        !own && modules > 0, own ? "You prepared this curriculum; someone else approves it."
                                : modules == 0 ? "Still being prepared: no modules yet." : null));
            });
            sheets("COMPUTED", Kind.RESULTS_RECOMMEND, "Computed; to be recommended", p, out);
            jdbc.query("""
                    select e.id, e.offering_id, e.requested_by_id, e.requested_by_name, e.requested_at, m.code, m.title, s.registration_no
                    from eligibility_exemptions e join module_offerings o on o.id = e.offering_id join modules m on m.id = o.module_id
                    join students s on s.id = e.student_id where e.status = 'PENDING'""", p, rs -> {
                boolean own = Objects.equals(rs.getLong("requested_by_id"), me);
                out.add(item(Kind.EXEMPTION, rs.getLong("id"), rs.getString("code") + " · " + rs.getString("title"),
                        "Exemption from the attendance rule for " + rs.getString("registration_no"), rs.getString("requested_by_name"),
                        rs.getObject("requested_at", OffsetDateTime.class), "/staff/eligibility/" + rs.getLong("offering_id"), !own,
                        own ? "You requested this exemption; someone else decides it." : null));
            });
        }
        if (admin) {
            sheets("RECOMMENDED", Kind.RESULTS_CONFIRM, "Recommended by the Academic Committee; to be confirmed", p, out);
            jdbc.query("""
                    select a.id, a.requested_by, a.requested_by_name, a.requested_at, s.registration_no, m.code
                    from result_amendments a join students s on s.id = a.student_id join module_results r on r.id = a.module_result_id
                    join module_offerings o on o.id = r.offering_id join modules m on m.id = o.module_id where a.status = 'PENDING'""", p, rs -> {
                boolean own = Objects.equals(rs.getObject("requested_by") == null ? null : rs.getLong("requested_by"), me);
                out.add(item(Kind.AMENDMENT, rs.getLong("id"), rs.getString("code") + " · " + rs.getString("registration_no"),
                        "Change to a confirmed result", rs.getString("requested_by_name"), rs.getObject("requested_at", OffsetDateTime.class),
                        "/staff/amendments", !own, own ? "You requested this amendment; someone else approves it." : null));
            });
            jdbc.query("""
                    select g.id, g.name, g.submitted_by_id, g.submitted_by_name, g.submitted_at,
                           (select count(*) from graduands x where x.graduation_id = g.id) as graduands
                    from graduations g where g.status = 'SUBMITTED'""", p, rs -> {
                out.add(item(Kind.GRADUATION, rs.getLong("id"), rs.getString("name"), rs.getLong("graduands") + " graduands",
                        rs.getString("submitted_by_name"), rs.getObject("submitted_at", OffsetDateTime.class), "/staff/graduations/" + rs.getLong("id"),
                        true, null));
            });
        }
        if (hod.any() || examsOffice) {
            jdbc.query("""
                    select o.id, o.status, o.resit_status, o.submitted_by_name, o.submitted_at, o.hod_by_name, o.hod_at, m.code, m.title,
                           m.department_id, se.name as semester
                    from module_offerings o join modules m on m.id = o.module_id join semesters se on se.id = o.semester_id
                    where o.status in ('SUBMITTED', 'HOD_APPROVED') or o.resit_status = 'SUBMITTED'""", p, rs -> {
                Long dept = rs.getObject("department_id") == null ? null : rs.getLong("department_id");
                boolean moderates = hod.covers(dept);
                String title = rs.getString("code") + " · " + rs.getString("title");
                String link = "/staff/marks/" + rs.getLong("id");
                String status = rs.getString("status");
                if ("SUBMITTED".equals(status) && moderates) {
                    out.add(item(Kind.MODULE_MODERATION, rs.getLong("id"), title, "Submitted by the lecturer · " + rs.getString("semester"),
                            rs.getString("submitted_by_name"), rs.getObject("submitted_at", OffsetDateTime.class), link, true, null));
                } else if ("HOD_APPROVED".equals(status) && (moderates || examsOffice)) {
                    out.add(item(Kind.MODULE_BOARD, rs.getLong("id"), title, "Moderated; Department Board approval to record · " + rs.getString("semester"),
                            rs.getString("hod_by_name"), rs.getObject("hod_at", OffsetDateTime.class), link, true, null));
                }
                if ("SUBMITTED".equals(rs.getString("resit_status")) && moderates) {
                    out.add(item(Kind.RESIT_MODERATION, rs.getLong("id"), title, "Resit marks submitted · " + rs.getString("semester"),
                            null, null, link, true, null));
                }
            });
            jdbc.query("""
                    select u.id, u.submitted_by_name, u.submitted_at, m.code, m.title, m.department_id, se.name as semester
                    from competence_units u join modules m on m.id = u.module_id join semesters se on se.id = u.semester_id
                    where u.status = 'SUBMITTED'""", p, rs -> {
                Long dept = rs.getObject("department_id") == null ? null : rs.getLong("department_id");
                if (hod.covers(dept) || admin) {
                    out.add(item(Kind.COMPETENCE_VERIFY, rs.getLong("id"), rs.getString("code") + " · " + rs.getString("title"),
                            "Competence outcomes submitted by the assessor; verify or return · " + rs.getString("semester"), rs.getString("submitted_by_name"),
                            rs.getObject("submitted_at", OffsetDateTime.class), "/staff/competence/" + rs.getLong("id"), true, null));
                }
            });
        }
        if (hod.any() || committee) {
            jdbc.query("""
                    select c.id, c.change_type, c.status, c.effective_date, c.requested_by_id, c.requested_by_name, c.requested_at,
                           c.recommended_by_name, c.recommended_at, s.registration_no, s.first_name, s.surname, p.department_id
                    from student_status_changes c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
                    where c.status in ('REQUESTED', 'RECOMMENDED')""", p, rs -> {
                Long dept = rs.getObject("department_id") == null ? null : rs.getLong("department_id");
                String type = rs.getString("change_type");
                String title = type.charAt(0) + type.substring(1).toLowerCase() + " · " + rs.getString("registration_no") + " "
                        + rs.getString("first_name") + " " + rs.getString("surname");
                String detail = "Effective " + rs.getObject("effective_date", java.time.LocalDate.class);
                boolean requested = "REQUESTED".equals(rs.getString("status"));
                if (committee) {
                    boolean own = Objects.equals(rs.getObject("requested_by_id") == null ? null : rs.getLong("requested_by_id"), me);
                    out.add(item(Kind.STATUS_DECISION, rs.getLong("id"), title,
                            detail + (requested ? " · not yet recommended by the Head of Department" : " · recommended by " + rs.getString("recommended_by_name")),
                            rs.getString("requested_by_name"), rs.getObject("requested_at", OffsetDateTime.class), "/staff/status-changes", !own,
                            own ? "You requested this change; someone else decides it." : null));
                } else if (requested && hod.covers(dept)) {
                    out.add(item(Kind.STATUS_RECOMMEND, rs.getLong("id"), title, detail + " · requested by Registry",
                            rs.getString("requested_by_name"), rs.getObject("requested_at", OffsetDateTime.class), "/staff/status-changes", true, null));
                }
            });
        }
        if (hod.any() || committee) {
            jdbc.query("""
                    select t.id, t.kind, t.state, t.requested_by_student, t.requested_by_id, t.requested_by_name, t.requested_at,
                           t.recommended_by_name, s.registration_no, s.first_name, s.surname, tp.code as to_code, tp.department_id
                    from student_transfers t join students s on s.id = t.student_id join programmes tp on tp.id = t.to_programme_id
                    where t.state in ('REQUESTED', 'RECOMMENDED')""", p, rs -> {
                Long dept = rs.getObject("department_id") == null ? null : rs.getLong("department_id");
                String kind = rs.getString("kind");
                String title = (kind.equals("CAMPUS") ? "Campus transfer" : "Transfer to " + rs.getString("to_code")) + " · "
                        + rs.getString("registration_no") + " " + rs.getString("first_name") + " " + rs.getString("surname");
                boolean requested = "REQUESTED".equals(rs.getString("state"));
                String who = rs.getBoolean("requested_by_student") ? "asked by the student" : "requested by Registry";
                if (committee) {
                    boolean own = !rs.getBoolean("requested_by_student")
                            && Objects.equals(rs.getObject("requested_by_id") == null ? null : rs.getLong("requested_by_id"), me);
                    String detail = who + (kind.equals("CAMPUS") ? "" : requested ? " · not yet recommended by the Head of Department"
                            : " · recommended by " + rs.getString("recommended_by_name"));
                    out.add(item(Kind.TRANSFER_DECISION, rs.getLong("id"), title, detail, rs.getString("requested_by_name"),
                            rs.getObject("requested_at", OffsetDateTime.class), "/staff/transfers", !own,
                            own ? "You requested this transfer; someone else decides it." : null));
                } else if (requested && !kind.equals("CAMPUS") && hod.covers(dept)) {
                    out.add(item(Kind.TRANSFER_RECOMMEND, rs.getLong("id"), title, who + " · into your department", rs.getString("requested_by_name"),
                            rs.getObject("requested_at", OffsetDateTime.class), "/staff/transfers", true, null));
                }
            });
        }
        if (examsOffice) {
            jdbc.query("""
                    select a.id, a.status, a.kind, a.requested_at, a.marked_at, a.examiner_name, s.registration_no, m.code,
                           (a.fee = 0 or a.invoice_id is null or exists (select 1 from invoices i where i.id = a.invoice_id and i.status = 'PAID')) as paid
                    from result_appeals a join students s on s.id = a.student_id join module_offerings o on o.id = a.offering_id
                    join modules m on m.id = o.module_id where a.status in ('REQUESTED', 'MARKED')""", p, rs -> {
                String what = ("REMARK".equals(rs.getString("kind")) ? "Remark" : "Appeal") + " · " + rs.getString("code") + " · " + rs.getString("registration_no");
                if ("MARKED".equals(rs.getString("status"))) {
                    out.add(item(Kind.APPEAL_FORWARD, rs.getLong("id"), what, "Marked by " + rs.getString("examiner_name") + "; to forward",
                            rs.getString("examiner_name"), rs.getObject("marked_at", OffsetDateTime.class), "/staff/appeals", true, null));
                } else if (rs.getBoolean("paid")) {
                    out.add(item(Kind.APPEAL_ASSIGN, rs.getLong("id"), what, "Fee paid; assign an examiner who did not teach the module",
                            rs.getString("registration_no"), rs.getObject("requested_at", OffsetDateTime.class), "/staff/appeals", true, null));
                }
            });
        }
        boolean bursar = admin || roles.contains(Role.BURSAR);
        boolean principal = admin || roles.contains(Role.MANAGEMENT_VIEWER);
        if (bursar || principal) {
            jdbc.query("""
                    select a.id, a.kind, a.status, a.amount, a.adjustment_no, a.requested_by_id, a.requested_by_name, a.requested_at, a.first_by_id,
                           a.first_by_name, a.first_at, s.registration_no
                    from finance_adjustments a join students s on s.id = a.student_id where a.status in ('PENDING', 'ENDORSED')""", p, rs -> {
                boolean pending = "PENDING".equals(rs.getString("status"));
                if ((pending && !bursar) || (!pending && !principal)) return;
                boolean own = Objects.equals(rs.getLong("requested_by_id"), me)
                        || (!pending && Objects.equals(rs.getObject("first_by_id") == null ? null : rs.getLong("first_by_id"), me));
                String what = (rs.getString("kind").charAt(0) + rs.getString("kind").substring(1).toLowerCase()) + " · " + rs.getString("registration_no") + " · TZS "
                        + String.format(java.util.Locale.ENGLISH, "%,.0f", rs.getBigDecimal("amount"));
                out.add(item(pending ? Kind.FIN_ADJUSTMENT : Kind.FIN_ADJUSTMENT_SECOND, rs.getLong("id"), what,
                        pending ? rs.getString("adjustment_no") + " · requested" : rs.getString("adjustment_no") + " · approved by the Bursar; above the threshold",
                        pending ? rs.getString("requested_by_name") : rs.getString("first_by_name"),
                        rs.getObject(pending ? "requested_at" : "first_at", OffsetDateTime.class), "/staff/waivers", !own,
                        own ? "You requested or approved this already; someone else decides it." : null));
            });
        }
        out.sort(Comparator.comparing(Item::canAct).reversed()
                .thenComparing(Item::submittedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new Inbox(out, out.size(), out.stream().filter(Item::canAct).count());
    }

    private void sheets(String status, Kind kind, String detail, MapSqlParameterSource p, List<Item> out) {
        jdbc.query("""
                select rs.id, rs.year_of_study, rs.round, rs.computed_by_name, rs.computed_at, rs.recommended_by_name, rs.recommended_at,
                       p.code, p.name, se.name as semester, (select count(*) from semester_results r where r.result_sheet_id = rs.id) as students
                from result_sheets rs join programmes p on p.id = rs.programme_id join semesters se on se.id = rs.semester_id
                where rs.status = :status""", new MapSqlParameterSource(p.getValues()).addValue("status", status), rs -> {
            boolean confirm = kind == Kind.RESULTS_CONFIRM;
            out.add(item(kind, rs.getLong("id"), rs.getString("code") + " year " + rs.getInt("year_of_study") + " · " + rs.getString("semester")
                            + ("RESIT".equals(rs.getString("round")) ? " (after resits)" : ""),
                    detail + " · " + rs.getLong("students") + " students",
                    confirm ? rs.getString("recommended_by_name") : rs.getString("computed_by_name"),
                    rs.getObject(confirm ? "recommended_at" : "computed_at", OffsetDateTime.class), "/staff/results/" + rs.getLong("id"), true, null));
        });
    }

    private static Item item(Kind k, Long id, String title, String detail, String by, OffsetDateTime at, String link, boolean canAct, String note) {
        return new Item(k.name(), k.label, k.body, id, title, detail, by, at, link, canAct, note);
    }

    private static String w(String s) {
        return s == null ? "Admission window" : s;
    }

    /** The departments whose module results this Head of Department moderates (all, for an institution-wide Head). */
    private record Departments(boolean all, Set<Long> ids) {
        boolean any() {
            return all || !ids.isEmpty();
        }

        boolean covers(Long departmentId) {
            return all || (departmentId != null && ids.contains(departmentId));
        }
    }

    private Departments headedDepartments(Long me, Set<Role> roles) {
        if (!roles.contains(Role.HEAD_OF_DEPARTMENT)) return new Departments(false, Set.of());
        UserAccount u = users.findById(me).orElse(null);
        if (u == null) return new Departments(false, Set.of());
        Set<Long> ids = new HashSet<>();
        for (UserAccount.RoleAssignment r : u.getRoles()) {
            if (r.getRole() != Role.HEAD_OF_DEPARTMENT) continue;
            if (!"DEPARTMENT".equals(r.getScopeType())) return new Departments(true, Set.of());
            if (r.getScopeId() != null) ids.add(r.getScopeId());
        }
        return new Departments(false, ids);
    }

    private static Set<Role> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5)).filter(n -> java.util.Arrays.stream(Role.values()).anyMatch(r -> r.name().equals(n)))
                .map(Role::valueOf).collect(Collectors.toSet());
    }
}
