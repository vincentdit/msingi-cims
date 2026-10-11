package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.math.BigDecimal;
import java.util.List;

import static tz.co.msingi.cims.reporting.ReportModel.MODULE;
import static tz.co.msingi.cims.reporting.ReportModel.PROGRAMME;
import static tz.co.msingi.cims.reporting.ReportModel.SEMESTER;

/** Teaching and eligibility reports (FR-TCH-04, FR-EXM-04): attendance per module and the examination eligibility list. */
@Component
class TeachingReports extends ReportQueries implements ReportProvider {

    TeachingReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("TCH-01", "Teaching", "Attendance by module",
                        "Per module in a semester: lecturers, sessions held, hours taught, average attendance and students below the examination threshold.",
                        ExamReports.ROLES, false, List.of(SEMESTER), this::attendance),
                new Definition("EXM-04", "Examinations", "Examination eligibility list",
                        "Each student's attendance, continuous assessment and fees clearance for the semester examination, exemptions and reasons.",
                        ExamReports.ROLES, true, List.of(SEMESTER, MODULE, PROGRAMME), this::eligibility));
    }

    private Data attendance(Filter f) {
        if (f.semesterId() == null) return new Data(List.of(Column.text("note", "Choose a semester")), List.of()).withNote("Choose the semester to report on.");
        Sql q = new Sql().param("sem", f.semesterId());
        List<List<Object>> r = rows("""
                with t as (
                    select cs.offering_id, ar.module_registration_id,
                           count(*) filter (where ar.status <> 'EXCUSED') counted,
                           count(*) filter (where ar.status in ('PRESENT', 'LATE')) attended
                    from attendance_records ar join class_sessions cs on cs.id = ar.session_id
                    join module_offerings o on o.id = cs.offering_id where o.semester_id = :sem group by 1, 2),
                th as (select coalesce((select min_attendance_percent from exam_settings limit 1), 75) v)
                select m.code, m.title, d.name,
                       (select string_agg(u.full_name, ', ' order by u.full_name) from offering_staff os join user_accounts u on u.id = os.user_id
                        where os.offering_id = o.id),
                       (select count(*) from class_sessions cs where cs.offering_id = o.id),
                       (select round(coalesce(sum(cs.duration_minutes), 0) / 60.0, 1) from class_sessions cs where cs.offering_id = o.id),
                       (select count(*) from t where t.offering_id = o.id and t.counted > 0),
                       (select round(avg(100.0 * t.attended / t.counted), 1) / 100 from t where t.offering_id = o.id and t.counted > 0),
                       (select count(*) from t, th where t.offering_id = o.id and t.counted > 0 and 100.0 * t.attended / t.counted < th.v),
                       (select max(cs.session_date) from class_sessions cs where cs.offering_id = o.id)
                from module_offerings o join modules m on m.id = o.module_id left join departments d on d.id = m.department_id
                where o.semester_id = :sem order by m.code""", q);
        r.forEach(x -> {
            if (x.get(7) instanceof BigDecimal b) x.set(7, b.doubleValue());
        });
        return new Data(List.of(Column.text("code", "Code"), Column.text("module", "Module"), Column.text("department", "Department"),
                Column.text("lecturers", "Lecturers"), Column.number("sessions", "Sessions"), Column.decimal("hours", "Hours taught"),
                Column.number("students", "Students recorded"), Column.percent("average", "Average attendance"),
                Column.number("below", "Below threshold"), Column.date("last", "Last session")), r)
                .withNote("Late counts as attended; excused absences are left out. The threshold is set under Examinations › Regulations.");
    }

    private Data eligibility(Filter f) {
        if (f.semesterId() == null) return new Data(List.of(Column.text("note", "Choose a semester")), List.of()).withNote("Choose the semester to report on.");
        Sql q = new Sql().param("sem", f.semesterId()).eq("o.module_id", "module", f.moduleId()).eq("p.id", "programme", f.programmeId());
        List<List<Object>> r = rows("""
                select m.code, s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), p.code,
                       e.attendance_percent / 100, e.attendance_met, e.ca_met, e.clearance_met, e.exempted, e.eligible,
                       (select string_agg(x, '; ') from jsonb_array_elements_text(e.eligibility_detail) x), e.generated_at
                from exam_eligibility e join module_offerings o on o.id = e.offering_id join modules m on m.id = o.module_id
                join students s on s.id = e.student_id join programmes p on p.id = s.programme_id
                where o.semester_id = :sem""" + q + " order by m.code, s.registration_no", q);
        r.forEach(x -> {
            if (x.get(5) instanceof BigDecimal b) x.set(5, b.doubleValue());
            for (int i = 6; i <= 10; i++) x.set(i, Boolean.TRUE.equals(x.get(i)) ? "Yes" : "No");
        });
        return new Data(List.of(Column.text("module", "Module"), Column.text("regNo", "Registration no."), Column.text("surname", "Surname"),
                Column.text("names", "Other names"), Column.text("programme", "Programme"), Column.percent("attendance", "Attendance"),
                Column.text("attendanceMet", "Attendance met"), Column.text("caMet", "CA complete"), Column.text("clearance", "Fees cleared"),
                Column.text("exempted", "Exempted"), Column.text("eligible", "Eligible"), Column.text("reasons", "Reasons"),
                Column.dateTime("generated", "List generated")), r)
                .withNote(r.isEmpty() ? "No eligibility list has been generated for this semester yet." : "From the latest list the Examinations Officer generated.");
    }
}
