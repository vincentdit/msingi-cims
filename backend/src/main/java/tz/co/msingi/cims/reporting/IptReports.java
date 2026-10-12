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
import static tz.co.msingi.cims.reporting.ReportModel.SEMESTER;

/** Industrial practical training reports (FR-IPT-01..03): placements by organisation and the placement list with marks. */
@Component
class IptReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.IPT_COORDINATOR, Role.REGISTRAR, Role.DEPUTY_PRINCIPAL_ACADEMIC,
            Role.HEAD_OF_DEPARTMENT, Role.EXAMINATIONS_OFFICER, Role.MANAGEMENT_VIEWER, Role.AUDITOR);

    IptReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("IPT-01", "IPT", "Placements by organisation",
                        "Per organisation: students placed, requested, completed and the average IPT mark, for a semester's IPT periods.",
                        ROLES, false, List.of(SEMESTER), this::byOrganisation),
                new Definition("IPT-02", "IPT", "IPT placement list",
                        "Each student's placement: organisation, dates, supervisors, logbook weeks approved, the three marks and the IPT mark.",
                        ROLES, true, List.of(SEMESTER, PROGRAMME), this::list));
    }

    private Data byOrganisation(Filter f) {
        Sql q = new Sql().eq("pe.semester_id", "sem", f.semesterId());
        List<List<Object>> r = rows("""
                select o.name, o.region, o.sector, count(p.id) filter (where p.status in ('CONFIRMED', 'COMPLETED')),
                       count(p.id) filter (where p.status = 'REQUESTED'), count(p.id) filter (where p.status = 'COMPLETED'),
                       round(avg(p.total_mark), 1), o.capacity
                from placement_organisations o join ipt_placements p on p.organisation_id = o.id join ipt_periods pe on pe.id = p.period_id
                where p.status in ('REQUESTED', 'CONFIRMED', 'COMPLETED')""" + q + " group by o.id, o.name, o.region, o.sector, o.capacity order by o.name", q);
        r.forEach(x -> { if (x.get(6) instanceof BigDecimal b) x.set(6, b.doubleValue()); });
        return new Data(List.of(Column.text("organisation", "Organisation"), Column.text("region", "Region"), Column.text("sector", "Sector"),
                Column.number("placed", "Placed"), Column.number("requested", "Requested"), Column.number("completed", "Completed"),
                Column.decimal("mean", "Average IPT mark"), Column.number("capacity", "Capacity")), r);
    }

    private Data list(Filter f) {
        Sql q = new Sql().eq("pe.semester_id", "sem", f.semesterId()).eq("s.programme_id", "programme", f.programmeId());
        List<List<Object>> r = rows("""
                select pe.name, s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), pr.code, o.name, o.region, p.status,
                       p.starts_on, p.ends_on, p.workplace_supervisor_name, u.full_name,
                       (select count(*) from ipt_logbook_entries e where e.placement_id = p.id and e.status = 'APPROVED'),
                       (select a.score from ipt_assessments a where a.placement_id = p.id and a.kind = 'WORKPLACE'),
                       (select a.score from ipt_assessments a where a.placement_id = p.id and a.kind = 'INSTITUTION'),
                       (select a.score from ipt_assessments a where a.placement_id = p.id and a.kind = 'LOGBOOK'),
                       p.total_mark, p.transferred_at
                from ipt_placements p join ipt_periods pe on pe.id = p.period_id join students s on s.id = p.student_id
                join programmes pr on pr.id = s.programme_id join placement_organisations o on o.id = p.organisation_id
                left join user_accounts u on u.id = p.institution_supervisor_id
                where p.status in ('REQUESTED', 'CONFIRMED', 'COMPLETED')""" + q + " order by pe.starts_on desc, s.registration_no", q);
        r.forEach(x -> {
            x.set(7, AdmissionReports.label(String.valueOf(x.get(7))));
            for (int i = 13; i <= 16; i++) if (x.get(i) instanceof BigDecimal b) x.set(i, b.doubleValue());
        });
        return new Data(List.of(Column.text("period", "Period"), Column.text("regNo", "Registration no."), Column.text("surname", "Surname"),
                Column.text("names", "Other names"), Column.text("programme", "Programme"), Column.text("organisation", "Organisation"),
                Column.text("region", "Region"), Column.text("status", "Status"), Column.date("from", "From"), Column.date("to", "To"),
                Column.text("workplace", "Workplace supervisor"), Column.text("supervisor", "Institution supervisor"),
                Column.number("weeks", "Logbook weeks approved"), Column.decimal("wp", "Workplace mark"), Column.decimal("inst", "Institution mark"),
                Column.decimal("log", "Logbook mark"), Column.decimal("total", "IPT mark"), Column.dateTime("transferred", "Sent to results")), r);
    }
}
