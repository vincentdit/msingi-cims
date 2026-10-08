package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.ACADEMIC_YEAR;
import static tz.co.msingi.cims.reporting.ReportModel.AWARD;
import static tz.co.msingi.cims.reporting.ReportModel.INTAKE;
import static tz.co.msingi.cims.reporting.ReportModel.LEVEL;
import static tz.co.msingi.cims.reporting.ReportModel.PROGRAMME;
import static tz.co.msingi.cims.reporting.ReportModel.SEX;
import static tz.co.msingi.cims.reporting.ReportModel.STUDENT_STATUS;
import static tz.co.msingi.cims.reporting.ReportModel.YEAR_OF_STUDY;

/** Registry reports (SRS section 7). "Current students" are those admitted or registered. */
@Component
class RegistryReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.REGISTRAR, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.HEAD_OF_DEPARTMENT,
            Role.MANAGEMENT_VIEWER, Role.AUDITOR);
    private static final String BASE = " from students s join programmes p on p.id = s.programme_id where 1 = 1";

    RegistryReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        List<String> all = List.of(ACADEMIC_YEAR, INTAKE, PROGRAMME, AWARD, LEVEL, SEX, STUDENT_STATUS, YEAR_OF_STUDY);
        return List.of(
                new Definition("REG-01", "Registry", "Enrolment by programme and year",
                        "Students per programme and year of study: admitted, registered and other statuses, by sex.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, PROGRAMME, AWARD, LEVEL, SEX, YEAR_OF_STUDY), this::enrolment),
                new Definition("REG-02", "Registry", "Nominal roll", "The list of students with registration number, programme, year and status.",
                        ROLES, true, all, this::nominalRoll),
                new Definition("REG-03", "Registry", "Age profile", "Current students by age band and sex.", ROLES, false, all, this::ages),
                new Definition("REG-04", "Registry", "Region profile", "Current students by home region and sex.", ROLES, false, all, this::regions),
                new Definition("REG-05", "Registry", "First registration status",
                        "Students handed over by Admissions: registered at reporting, and still to report.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, PROGRAMME, AWARD, LEVEL, SEX), this::firstRegistration));
    }

    private Sql filters(Filter f, boolean withStatus) {
        Sql q = new Sql().eq("s.entry_academic_year_id", "year", f.academicYearId()).eq("s.intake_id", "intake", f.intakeId())
                .eq("p.id", "programme", f.programmeId()).eq("p.award_family", "award", f.awardFamily()).eq("p.award_level", "level", f.level())
                .eq("s.sex", "sex", f.sex()).eq("s.year_of_study", "yos", f.yearOfStudy() == null ? null : f.yearOfStudy().shortValue());
        if (withStatus) {
            if (f.status() == null || f.status().isBlank()) q.raw("s.status in ('ADMITTED', 'ACTIVE')");
            else q.eq("s.status", "status", f.status());
        }
        return q;
    }

    private Data enrolment(Filter f) {
        Sql q = filters(f, false);
        List<List<Object>> r = rows("""
                select p.code, p.name, s.year_of_study,
                       count(*) filter (where s.status = 'ADMITTED'), count(*) filter (where s.status = 'ACTIVE'),
                       count(*) filter (where s.status not in ('ADMITTED', 'ACTIVE')),
                       count(*) filter (where s.sex = 'F' and s.status in ('ADMITTED', 'ACTIVE')),
                       count(*) filter (where s.sex = 'M' and s.status in ('ADMITTED', 'ACTIVE')),
                       count(*) filter (where s.status in ('ADMITTED', 'ACTIVE'))""" + BASE + q
                + " group by p.code, p.name, s.year_of_study order by p.name, s.year_of_study", q);
        return new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("year", "Year"),
                Column.number("admitted", "Admitted, not yet registered"), Column.number("registered", "Registered"),
                Column.number("other", "Other statuses"), Column.number("female", "Female (current)"), Column.number("male", "Male (current)"),
                Column.number("current", "Current students")), r).withTotals()
                .withNote("Current students = admitted or registered. Other statuses: postponed, suspended, discontinued, withdrawn, completed, graduated.");
    }

    private Data nominalRoll(Filter f) {
        Sql q = filters(f, true);
        List<List<Object>> r = rows("""
                select s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex, s.date_of_birth, s.nationality,
                       p.code, s.year_of_study, y.code, s.status, s.phone, s.email
                from students s join programmes p on p.id = s.programme_id join academic_years y on y.id = s.entry_academic_year_id
                where 1 = 1""" + q + " order by p.code, s.year_of_study, s.surname, s.first_name", q);
        r.forEach(x -> x.set(9, AdmissionReports.label(String.valueOf(x.get(9)))));
        return new Data(List.of(Column.text("regNo", "Registration no."), Column.text("surname", "Surname"), Column.text("names", "Other names"),
                Column.text("sex", "Sex"), Column.date("dob", "Date of birth"), Column.text("nationality", "Nationality"),
                Column.text("programme", "Programme"), Column.number("year", "Year"), Column.text("entry", "Entry year"),
                Column.text("status", "Status"), Column.text("phone", "Phone"), Column.text("email", "Email")), r);
    }

    private Data ages(Filter f) {
        Sql q = filters(f, true).param("today", LocalDate.now(Sql.ZONE));
        List<List<Object>> raw = rows("""
                select case when a < 18 then 'Under 18' when a <= 20 then '18–20' when a <= 24 then '21–24' when a <= 29 then '25–29'
                            when a <= 39 then '30–39' else '40 and over' end as band,
                       count(*) filter (where sex = 'F'), count(*) filter (where sex = 'M'), count(*)
                from (select date_part('year', age(cast(:today as date), s.date_of_birth)) as a, s.sex""" + BASE + q + ") t group by 1", q);
        List<String> order = List.of("Under 18", "18–20", "21–24", "25–29", "30–39", "40 and over");
        List<List<Object>> r = new ArrayList<>();
        for (String band : order) {
            r.add(raw.stream().filter(x -> band.equals(x.get(0))).findFirst().orElse(new ArrayList<>(List.of(band, 0L, 0L, 0L))));
        }
        long total = r.stream().mapToLong(x -> num(x.get(3))).sum();
        r.forEach(x -> x.add(ratio(x.get(3), total)));
        Data d = new Data(List.of(Column.text("band", "Age"), Column.number("female", "Female"), Column.number("male", "Male"),
                Column.number("total", "Students"), Column.percent("share", "Share")), r).withTotals();
        d.totals().set(4, total == 0 ? null : 1.0);
        return d.withNote("Age today.");
    }

    private Data regions(Filter f) {
        Sql q = filters(f, true);
        List<List<Object>> r = rows("select coalesce(nullif(s.region, ''), 'Not given'), count(*) filter (where s.sex = 'F'), count(*) filter (where s.sex = 'M'), count(*)"
                + BASE + q + " group by 1 order by 4 desc, 1", q);
        long total = r.stream().mapToLong(x -> num(x.get(3))).sum();
        r.forEach(x -> x.add(ratio(x.get(3), total)));
        Data d = new Data(List.of(Column.text("region", "Region"), Column.number("female", "Female"), Column.number("male", "Male"),
                Column.number("total", "Students"), Column.percent("share", "Share")), r).withTotals();
        d.totals().set(4, total == 0 ? null : 1.0);
        return d;
    }

    private Data firstRegistration(Filter f) {
        Sql q = filters(f, false);
        List<List<Object>> r = rows("""
                select p.code, p.name, count(*), count(*) filter (where fr.status = 'APPROVED'), count(*) filter (where fr.status = 'PENDING' and fr.reported_at is not null),
                       count(*) filter (where fr.status = 'PENDING' and fr.reported_at is null)
                from students s join programmes p on p.id = s.programme_id join first_registrations fr on fr.student_id = s.id
                where 1 = 1""" + q + " group by p.code, p.name order by p.name", q);
        r.forEach(x -> x.add(ratio(x.get(3), x.get(2))));
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("handed", "Handed over"),
                Column.number("registered", "Registered"), Column.number("checking", "Reported, being checked"), Column.number("notReported", "Not yet reported"),
                Column.percent("rate", "Registered %")), r).withTotals();
        d.totals().set(6, ratio(d.totals().get(3), d.totals().get(2)));
        return d;
    }
}
