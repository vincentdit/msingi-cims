package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.PROGRAMME;

/** Clearance and graduation reports (FR-GRD-01/02): clearance status by office and the graduand list. */
@Component
class GraduationReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.REGISTRAR, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER, Role.AUDITOR);

    GraduationReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("GRD-01", "Graduation", "Clearance status",
                        "Each finalist's clearance: started, offices cleared, held (with the office and reason) and when complete.",
                        ROLES, true, List.of(PROGRAMME), this::clearance),
                new Definition("GRD-02", "Graduation", "Graduand list",
                        "Graduands by graduation and programme with award, classification, GPA, Board approval and certificate numbers.",
                        ROLES, true, List.of(PROGRAMME), this::graduands));
    }

    private Data clearance(Filter f) {
        Sql q = new Sql().eq("p.id", "programme", f.programmeId());
        List<List<Object>> r = rows("""
                select s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), p.code, c.status, c.started_at,
                       count(i.id) filter (where i.status = 'CLEARED'), count(i.id),
                       string_agg(case when i.status = 'HELD' then o.name || ': ' || i.note end, '; ' order by o.sort_order),
                       string_agg(case when i.status = 'PENDING' then o.name end, ', ' order by o.sort_order), c.cleared_at
                from clearances c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
                join clearance_items i on i.clearance_id = c.id join clearance_offices o on o.id = i.office_id
                where true""" + q + " group by c.id, s.registration_no, s.surname, s.first_name, s.middle_name, p.code order by s.registration_no", q);
        r.forEach(x -> x.set(4, "CLEARED".equals(x.get(4)) ? "Cleared" : "In progress"));
        return new Data(List.of(Column.text("regNo", "Registration no."), Column.text("surname", "Surname"), Column.text("names", "Other names"),
                Column.text("programme", "Programme"), Column.text("status", "Status"), Column.dateTime("started", "Started"),
                Column.number("cleared", "Offices cleared"), Column.number("offices", "Offices"), Column.text("held", "Held by"),
                Column.text("pending", "Waiting for"), Column.dateTime("completed", "Completed")), r);
    }

    private Data graduands(Filter f) {
        Sql q = new Sql().eq("p.id", "programme", f.programmeId());
        List<List<Object>> r = rows("""
                select g.name, g.status, p.code, s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex, gr.award_name,
                       gr.classification, gr.cgpa, gr.certificate_no, g.ceremony_date
                from graduands gr join graduations g on g.id = gr.graduation_id
                join students s on s.id = gr.student_id join programmes p on p.id = gr.programme_id
                where true""" + q + " order by g.created_at desc, p.code, s.registration_no", q);
        r.forEach(x -> {
            x.set(1, switch (String.valueOf(x.get(1))) { case "APPROVED" -> "Approved by the Board"; case "SUBMITTED" -> "With the Board"; default -> "Draft"; });
            if (x.get(9) instanceof BigDecimal b) x.set(9, b.doubleValue());
        });
        return new Data(List.of(Column.text("graduation", "Graduation"), Column.text("stage", "Stage"), Column.text("programme", "Programme"),
                Column.text("regNo", "Registration no."), Column.text("surname", "Surname"), Column.text("names", "Other names"), Column.text("sex", "Sex"),
                Column.text("award", "Award"), Column.text("classification", "Classification"), Column.decimal("gpa", "GPA"),
                Column.text("certificate", "Certificate no."), Column.date("ceremony", "Ceremony")), r);
    }
}
