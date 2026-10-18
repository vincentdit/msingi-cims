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
import static tz.co.msingi.cims.reporting.ReportModel.MODULE;
import static tz.co.msingi.cims.reporting.ReportModel.SEMESTER;
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
                new Definition("REG-06", "Registry", "Semester registration status",
                        "Registered students per programme for a semester: submitted, approved, returned, and still to register.", ROLES, false,
                        List.of(SEMESTER, PROGRAMME, AWARD, LEVEL, SEX, YEAR_OF_STUDY), this::semesterStatus),
                new Definition("REG-07", "Registry", "Module class list", "Students whose registration for the module in the semester is approved.",
                        ROLES, true, List.of(SEMESTER, MODULE, PROGRAMME, SEX), this::classList),
                new Definition("REG-05", "Registry", "First registration status",
                        "Students handed over by Admissions: registered at reporting, and still to report.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, PROGRAMME, AWARD, LEVEL, SEX), this::firstRegistration),
                new Definition("REG-08", "Registry", "Health insurance (NHIF) cover",
                        "Current students per programme with active health cover (own NHIF card or through the institution), registration under way, "
                                + "problems to sort out and no cover recorded, for an academic year.", ROLES, false,
                        List.of(ACADEMIC_YEAR, PROGRAMME, AWARD, LEVEL, SEX, YEAR_OF_STUDY), this::healthCover),
                new Definition("REG-09", "Registry", "Students without health cover",
                        "Current students not covered for the academic year: no cover recorded, card not accepted or expired, NHIF refused the "
                                + "registration, or contribution not paid.", ROLES, true,
                        List.of(ACADEMIC_YEAR, PROGRAMME, AWARD, LEVEL, SEX, YEAR_OF_STUDY), this::withoutCover));
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

    private Data semesterStatus(Filter f) {
        if (f.semesterId() == null) return new Data(List.of(Column.text("note", "Choose a semester")), List.of()).withNote("Choose the semester to report on.");
        Sql q = filters(f, false).param("sem", f.semesterId());
        List<List<Object>> r = rows("""
                select p.code, p.name, count(*) filter (where s.status = 'ACTIVE'),
                       count(*) filter (where sr.status = 'SUBMITTED'), count(*) filter (where sr.status = 'APPROVED'),
                       count(*) filter (where sr.status = 'REJECTED'), count(*) filter (where sr.late and sr.status in ('SUBMITTED', 'APPROVED')),
                       count(*) filter (where s.status = 'ACTIVE' and (sr.id is null or sr.status in ('CANCELLED', 'REJECTED')))
                from students s join programmes p on p.id = s.programme_id
                left join semester_registrations sr on sr.student_id = s.id and sr.semester_id = :sem
                where (s.status = 'ACTIVE' or sr.id is not null)""" + q + " group by p.code, p.name order by p.name", q);
        r.forEach(x -> x.add(ratio(x.get(4), x.get(2))));
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("active", "Registered students"),
                Column.number("submitted", "Awaiting approval"), Column.number("approved", "Approved"), Column.number("returned", "Returned"),
                Column.number("late", "Late"), Column.number("missing", "Not yet registered"), Column.percent("rate", "Approved %")), r).withTotals();
        d.totals().set(8, ratio(d.totals().get(4), d.totals().get(2)));
        return d.withNote("Registered students = students whose first registration is complete (status Registered).");
    }

    private Data classList(Filter f) {
        if (f.semesterId() == null || f.moduleId() == null) {
            return new Data(List.of(Column.text("note", "Choose a semester and a module")), List.of()).withNote("Choose the semester and the module.");
        }
        Sql q = new Sql().param("sem", f.semesterId()).param("module", f.moduleId()).eq("p.id", "programme", f.programmeId()).eq("s.sex", "sex", f.sex());
        List<List<Object>> r = rows("""
                select s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex, p.code, sr.year_of_study, mr.category, mr.attempt
                from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                join students s on s.id = mr.student_id join programmes p on p.id = s.programme_id
                where sr.semester_id = :sem and mr.module_id = :module and sr.status = 'APPROVED'""" + q + " order by s.surname, s.first_name", q);
        r.forEach(x -> {
            x.set(6, AdmissionReports.label(String.valueOf(x.get(6))));
            x.set(7, AdmissionReports.label(String.valueOf(x.get(7))));
        });
        return new Data(List.of(Column.text("regNo", "Registration no."), Column.text("surname", "Surname"), Column.text("names", "Other names"),
                Column.text("sex", "Sex"), Column.text("programme", "Programme"), Column.number("year", "Year"), Column.text("category", "Core / elective"),
                Column.text("attempt", "Attempt")), r);
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

    /** The cover year (the chosen academic year, else the current one) and the programme filters, for current students. */
    private Sql coverFilters(Filter f) {
        Sql q = new Sql().eq("p.id", "programme", f.programmeId()).eq("p.award_family", "award", f.awardFamily()).eq("p.award_level", "level", f.level())
                .eq("s.sex", "sex", f.sex()).eq("s.year_of_study", "yos", f.yearOfStudy() == null ? null : f.yearOfStudy().shortValue())
                .raw("s.status in ('ADMITTED', 'ACTIVE')").param("today", LocalDate.now(Sql.ZONE));
        if (f.academicYearId() != null) return q.param("coverYear", f.academicYearId());
        Long current = jdbc.getJdbcTemplate().queryForList(
                "select id from academic_years order by (current_date between start_date and end_date) desc, start_date desc limit 1", Long.class)
                .stream().findFirst().orElse(-1L);
        return q.param("coverYear", current);
    }

    private static final String COVER_JOIN = " from students s join programmes p on p.id = s.programme_id"
            + " left join health_covers c on c.student_id = s.id and c.academic_year_id = :coverYear"
            + " left join invoices i on i.id = c.invoice_id where 1 = 1";

    /** As shown: ACTIVE (valid), UNDER_WAY (contribution billed, paid or with NHIF), PROBLEM (not accepted, expired, refused) or NONE. */
    private static final String COVER_STATE = """
            case when c.id is null then 'NONE'
                 when c.status = 'ACTIVE' and (c.valid_to is null or c.valid_to >= :today) then 'ACTIVE'
                 when c.status in ('AWAITING_PAYMENT', 'READY', 'SUBMITTED') then 'UNDER_WAY'
                 else 'PROBLEM' end""";

    private Data healthCover(Filter f) {
        Sql q = coverFilters(f);
        List<List<Object>> r = rows("select p.code, p.name, count(*), count(*) filter (where (" + COVER_STATE + ") = 'ACTIVE'), "
                + "count(*) filter (where (" + COVER_STATE + ") = 'ACTIVE' and c.route = 'OWN_CARD'), "
                + "count(*) filter (where (" + COVER_STATE + ") = 'UNDER_WAY'), count(*) filter (where (" + COVER_STATE + ") = 'PROBLEM'), "
                + "count(*) filter (where c.id is null)" + COVER_JOIN + q + " group by p.code, p.name order by p.name", q);
        r.forEach(x -> x.add(ratio(x.get(3), x.get(2))));
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("students", "Current students"),
                Column.number("covered", "Covered"), Column.number("ownCard", "of which own card"), Column.number("underWay", "Registration under way"),
                Column.number("problems", "Needs attention"), Column.number("none", "No cover recorded"), Column.percent("rate", "Covered %")), r).withTotals();
        d.totals().set(8, ratio(d.totals().get(3), d.totals().get(2)));
        return d.withNote("Covered: an NHIF card checked with NHIF and not expired, or issued after registration through the institution. "
                + "Registration under way: NHIF contribution billed, paid, or sent to NHIF. Needs attention: card not accepted, expired, or NHIF refused the registration.");
    }

    private Data withoutCover(Filter f) {
        Sql q = coverFilters(f).raw("(" + COVER_STATE + ") <> 'ACTIVE'").raw("coalesce(c.status, '') not in ('READY', 'SUBMITTED')");
        List<List<Object>> r = rows("""
                select s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex, p.code, s.year_of_study, s.phone,
                       case when c.id is null then 'No cover recorded'
                            when c.status = 'ACTIVE' then 'Card expired'
                            when c.status = 'INVALID' then 'Card not accepted'
                            when c.status = 'REJECTED' then 'Refused by NHIF'
                            when c.status = 'AWAITING_PAYMENT' then 'Contribution not paid' else c.status end, c.reason""" + COVER_JOIN + q
                + " order by p.code, s.year_of_study, s.surname, s.first_name", q);
        return new Data(List.of(Column.text("regNo", "Registration no."), Column.text("surname", "Surname"), Column.text("names", "Other names"),
                Column.text("sex", "Sex"), Column.text("programme", "Programme"), Column.number("year", "Year"), Column.text("phone", "Phone"),
                Column.text("state", "Situation"), Column.text("reason", "Reason")), r);
    }
}
