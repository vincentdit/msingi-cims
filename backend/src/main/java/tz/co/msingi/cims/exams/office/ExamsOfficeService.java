package tz.co.msingi.cims.exams.office;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.NotFoundException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Examinations Office board: for one semester, where every step of BP-07 to BP-11 stands — marks entry and moderation per
 * module, examination eligibility, class result sheets through approval and publication, supplementary and special examinations,
 * remarks and appeals, amendments and NACTVET results files — with the next things to do, each pointing to its page.
 */
@Service
@RequiredArgsConstructor
public class ExamsOfficeService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;

    public record SemesterOption(Long id, String name, String academicYear, LocalDate startDate, LocalDate endDate) {
    }

    public record MarksWindow(LocalDate opens, LocalDate closes, String state) {
    }

    public record ModuleRow(Long offeringId, String code, String title, String department, String status, String lecturers, int students,
                            OffsetDateTime since) {
    }

    public record Modules(long total, long draft, long submitted, long hodApproved, long boardApproved, long withoutLecturer,
                          List<ModuleRow> behind) {
    }

    public record Eligibility(long modulesGenerated, long modules, long students, long ineligible, long pendingExemptions) {
    }

    public record SheetRow(Long id, String programme, int yearOfStudy, String round, String status, int students, OffsetDateTime since,
                           boolean nactvetSubmitted, String nactvetStatus) {
    }

    public record Sheets(long classes, long computed, long recommended, long confirmed, long published, List<SheetRow> sheets,
                         List<String> classesWithoutSheet) {
    }

    public record Resits(boolean open, LocalDate registrationCloses, long registered, long unpaid, long marksSubmitted, long marksApproved) {
    }

    public record Appeals(long requested, long awaitingPayment, long withExaminer, long toForward, long withAdministrator, long closed) {
    }

    public record Step(String severity, String text, String link) {
    }

    public record Board(List<SemesterOption> semesters, SemesterOption semester, MarksWindow marksWindow, Modules modules,
                        Eligibility eligibility, Sheets sheets, Resits resits, Appeals appeals, long amendmentsPending, List<Step> next) {
    }

    @Transactional(readOnly = true)
    public Board board(Long semesterId) {
        List<SemesterOption> semesters = jdbc.query("""
                select s.id, s.name, y.code, s.start_date, s.end_date from semesters s join academic_years y on y.id = s.academic_year_id
                order by s.start_date desc""", (r, i) -> new SemesterOption(r.getLong("id"), r.getString("name"), r.getString("code"),
                r.getObject("start_date", LocalDate.class), r.getObject("end_date", LocalDate.class)));
        if (semesters.isEmpty()) {
            return new Board(List.of(), null, null, null, null, null, null, null, 0,
                    List.of(new Step("info", "No semester is set up yet. Add one on the academic calendar.", "/staff/calendar")));
        }
        LocalDate today = LocalDate.now(ZONE);
        SemesterOption sem = semesterId != null
                ? semesters.stream().filter(s -> s.id().equals(semesterId)).findFirst().orElseThrow(() -> new NotFoundException("Semester", semesterId))
                // the semester under way, else the latest that has started, else the earliest
                : semesters.stream().filter(s -> !s.startDate().isAfter(today) && !s.endDate().isBefore(today)).findFirst()
                .orElse(semesters.stream().filter(s -> !s.startDate().isAfter(today)).findFirst().orElse(semesters.get(semesters.size() - 1)));
        Long sid = sem.id();

        Map<String, Object> w = jdbc.queryForMap("select marks_entry_opens, marks_entry_closes from semesters where id = ?", sid);
        LocalDate opens = (LocalDate) toDate(w.get("marks_entry_opens")), closes = (LocalDate) toDate(w.get("marks_entry_closes"));
        String state = opens == null && closes == null ? "NOT_SET" : opens != null && today.isBefore(opens) ? "UPCOMING"
                : closes != null && today.isAfter(closes) ? "CLOSED" : "OPEN";
        MarksWindow window = new MarksWindow(opens, closes, state);

        Map<String, Object> mc = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'DRAFT') as draft, count(*) filter (where status = 'SUBMITTED') as submitted,
                       count(*) filter (where status = 'HOD_APPROVED') as hod, count(*) filter (where status = 'BOARD_APPROVED') as board,
                       count(*) filter (where not exists (select 1 from offering_staff st where st.offering_id = o.id)) as nolect
                from module_offerings o where o.semester_id = ?""", sid);
        List<ModuleRow> behind = jdbc.query("""
                select o.id, m.code, m.title, d.name as dept, o.status,
                       (select string_agg(u.full_name, ', ' order by u.full_name) from offering_staff st join user_accounts u on u.id = st.user_id
                        where st.offering_id = o.id) as lecturers,
                       (select count(*) from module_results r where r.offering_id = o.id) as students,
                       coalesce(o.submitted_at, o.updated_at) as since
                from module_offerings o join modules m on m.id = o.module_id left join departments d on d.id = m.department_id
                where o.semester_id = ? and o.status in ('DRAFT', 'SUBMITTED')
                order by case o.status when 'SUBMITTED' then 0 else 1 end, m.code limit 100""", (r, i) -> new ModuleRow(r.getLong("id"),
                r.getString("code"), r.getString("title"), r.getString("dept"), r.getString("status"), r.getString("lecturers"), r.getInt("students"),
                r.getObject("since", OffsetDateTime.class)), sid);
        Modules modules = new Modules(n(mc, "total"), n(mc, "draft"), n(mc, "submitted"), n(mc, "hod"), n(mc, "board"), n(mc, "nolect"), behind);

        Map<String, Object> ec = jdbc.queryForMap("""
                select count(distinct e.offering_id) as gen, count(distinct e.student_id) as students,
                       count(*) filter (where not e.eligible) as inel,
                       (select count(*) from module_offerings o where o.semester_id = ?) as modules,
                       (select count(*) from eligibility_exemptions x join module_offerings o on o.id = x.offering_id
                        where o.semester_id = ? and x.status = 'PENDING') as pend
                from exam_eligibility e join module_offerings o on o.id = e.offering_id where o.semester_id = ?""", sid, sid, sid);
        Eligibility eligibility = new Eligibility(n(ec, "gen"), n(ec, "modules"), n(ec, "students"), n(ec, "inel"), n(ec, "pend"));

        List<SheetRow> sheetRows = jdbc.query("""
                select s.id, p.code as programme, s.year_of_study, s.round, s.status,
                       (select count(*) from semester_results r where r.result_sheet_id = s.id) as students,
                       coalesce(s.published_at, s.confirmed_at, s.recommended_at, s.computed_at) as since,
                       (select n.status from nactvet_result_submissions n where n.result_sheet_id = s.id order by n.id desc limit 1) as nactvet
                from result_sheets s join programmes p on p.id = s.programme_id where s.semester_id = ?
                order by p.code, s.year_of_study, s.round""", (r, i) -> new SheetRow(r.getLong("id"), r.getString("programme"), r.getInt("year_of_study"),
                r.getString("round"), r.getString("status"), r.getInt("students"), r.getObject("since", OffsetDateTime.class),
                r.getString("nactvet") != null, r.getString("nactvet")), sid);
        List<String> classes = jdbc.queryForList("""
                select distinct p.code || ' year ' || r.year_of_study from semester_registrations r join students st on st.id = r.student_id
                join programmes p on p.id = st.programme_id where r.semester_id = ? and r.status = 'APPROVED'
                order by 1""", String.class, sid);
        List<String> withSheet = sheetRows.stream().filter(s -> "MAIN".equals(s.round())).map(s -> s.programme() + " year " + s.yearOfStudy()).toList();
        List<String> without = classes.stream().filter(c -> !withSheet.contains(c)).toList();
        List<SheetRow> main = sheetRows.stream().filter(s -> "MAIN".equals(s.round())).toList();
        Sheets sheets = new Sheets(classes.size(), count(main, "COMPUTED"), count(main, "RECOMMENDED"), count(main, "CONFIRMED"), count(main, "PUBLISHED"),
                sheetRows, without);

        Map<String, Object> rc = jdbc.queryForList("""
                select rs.registration_closes,
                       (select count(*) from resits r where r.session_id = rs.id and r.status = 'REGISTERED') as reg,
                       (select count(*) from resits r where r.session_id = rs.id and r.status = 'REGISTERED' and r.fee > 0 and r.invoice_id is not null
                          and not exists (select 1 from invoices i where i.id = r.invoice_id and i.status = 'PAID')) as unpaid,
                       (select count(*) from module_offerings o where o.semester_id = rs.semester_id and o.resit_status = 'SUBMITTED') as sub,
                       (select count(*) from module_offerings o where o.semester_id = rs.semester_id and o.resit_status = 'APPROVED') as appr
                from resit_sessions rs where rs.semester_id = ?""", sid).stream().findFirst().orElse(null);
        Resits resits = rc == null ? new Resits(false, null, 0, 0, 0, 0)
                : new Resits(true, (LocalDate) toDate(rc.get("registration_closes")), n(rc, "reg"), n(rc, "unpaid"), n(rc, "sub"), n(rc, "appr"));

        Map<String, Object> ac = jdbc.queryForMap("""
                select count(*) filter (where a.status = 'REQUESTED' and (a.fee = 0 or a.invoice_id is null
                         or exists (select 1 from invoices i where i.id = a.invoice_id and i.status = 'PAID'))) as req,
                       count(*) filter (where a.status = 'REQUESTED' and a.fee > 0 and a.invoice_id is not null
                         and not exists (select 1 from invoices i where i.id = a.invoice_id and i.status = 'PAID')) as unpaid,
                       count(*) filter (where a.status = 'ASSIGNED') as exam, count(*) filter (where a.status = 'MARKED') as fwd,
                       count(*) filter (where a.status = 'AMENDMENT_PENDING') as adm, count(*) filter (where a.status = 'CLOSED') as closed
                from result_appeals a join module_offerings o on o.id = a.offering_id where o.semester_id = ?""", sid);
        Appeals appeals = new Appeals(n(ac, "req"), n(ac, "unpaid"), n(ac, "exam"), n(ac, "fwd"), n(ac, "adm"), n(ac, "closed"));
        Long amendments = jdbc.queryForObject("select count(*) from result_amendments where status = 'PENDING'", Long.class);

        List<Step> next = new ArrayList<>();
        if ("NOT_SET".equals(state)) next.add(new Step("warning", "Set the marks entry window for " + sem.name() + ".", "/staff/calendar"));
        if (modules.withoutLecturer() > 0) next.add(new Step("warning", modules.withoutLecturer() + " module(s) have no lecturer allocated.", "/staff/marks"));
        if (modules.draft() > 0) next.add(new Step("CLOSED".equals(state) ? "error" : "info", modules.draft() + " module(s) still in marks entry"
                + ("CLOSED".equals(state) ? " although the window has closed." : "."), "/staff/marks"));
        if (modules.submitted() > 0) next.add(new Step("info", modules.submitted() + " module(s) wait for the Head of Department to moderate.", "/staff/marks"));
        if (modules.total() > 0 && eligibility.modulesGenerated() < modules.total()) {
            next.add(new Step("info", "Examination eligibility generated for " + eligibility.modulesGenerated() + " of " + modules.total() + " modules.", "/staff/eligibility"));
        }
        if (eligibility.pendingExemptions() > 0) next.add(new Step("warning", eligibility.pendingExemptions() + " eligibility exemption(s) wait for a decision.", "/staff/approvals"));
        if (!without.isEmpty() && modules.total() > 0 && modules.draft() + modules.submitted() == 0) {
            next.add(new Step("info", "Compute the result sheet for " + String.join(", ", without.subList(0, Math.min(5, without.size())))
                    + (without.size() > 5 ? " and " + (without.size() - 5) + " more" : "") + ".", "/staff/results"));
        }
        if (sheets.computed() > 0) next.add(new Step("info", sheets.computed() + " result sheet(s) wait for the Academic Committee's recommendation.", "/staff/results"));
        if (sheets.recommended() > 0) next.add(new Step("info", sheets.recommended() + " result sheet(s) wait for the Governing Board's confirmation.", "/staff/results"));
        if (sheets.confirmed() > 0) next.add(new Step("warning", sheets.confirmed() + " confirmed result sheet(s) are not published to students yet.", "/staff/results"));
        long notSent = sheetRows.stream().filter(s -> ("CONFIRMED".equals(s.status()) || "PUBLISHED".equals(s.status())) && !s.nactvetSubmitted()).count();
        if (notSent > 0) next.add(new Step("info", notSent + " confirmed result sheet(s) not yet submitted to NACTVET.", "/staff/results"));
        long queried = sheetRows.stream().filter(s -> "QUERIED".equals(s.nactvetStatus())).count();
        if (queried > 0) next.add(new Step("error", queried + " NACTVET results submission(s) were queried by NACTVET.", "/staff/results"));
        if (sheets.published() > 0 && !resits.open()) next.add(new Step("info", "Results are published: open the supplementary and special examinations session.", "/staff/results"));
        if (resits.unpaid() > 0) next.add(new Step("info", resits.unpaid() + " resit registration(s) are not paid yet.", "/staff/results"));
        if (appeals.requested() > 0) next.add(new Step("warning", appeals.requested() + " paid remark/appeal request(s) need an examiner.", "/staff/appeals"));
        if (appeals.toForward() > 0) next.add(new Step("warning", appeals.toForward() + " remark/appeal outcome(s) to forward.", "/staff/appeals"));
        if (amendments != null && amendments > 0) next.add(new Step("info", amendments + " result amendment(s) wait for the administrator.", "/staff/amendments"));
        if (next.isEmpty()) next.add(new Step("success", "Nothing is waiting in the Examinations Office for this semester.", null));
        return new Board(semesters, sem, window, modules, eligibility, sheets, resits, appeals, amendments == null ? 0 : amendments, next);
    }

    private static long count(List<SheetRow> rows, String status) {
        return rows.stream().filter(r -> status.equals(r.status())).count();
    }

    private static long n(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number x ? x.longValue() : 0;
    }

    private static Object toDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return o;
    }
}
