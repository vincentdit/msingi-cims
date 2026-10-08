package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.ACADEMIC_YEAR;
import static tz.co.msingi.cims.reporting.ReportModel.APPLICATION_STATUS;
import static tz.co.msingi.cims.reporting.ReportModel.AWARD;
import static tz.co.msingi.cims.reporting.ReportModel.INTAKE;
import static tz.co.msingi.cims.reporting.ReportModel.LEVEL;
import static tz.co.msingi.cims.reporting.ReportModel.PROGRAMME;
import static tz.co.msingi.cims.reporting.ReportModel.SEX;
import static tz.co.msingi.cims.reporting.ReportModel.WINDOW;

/** Admissions reports (SRS section 7; FR-ADM-16). Applications count once submitted (draft and unpaid ones are left out). */
@Component
class AdmissionReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.ADMISSIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.REGISTRAR,
            Role.MANAGEMENT_VIEWER, Role.AUDITOR);
    private static final String BASE = """
            from applications a
            join applicants ap on ap.id = a.applicant_id
            join admission_windows w on w.id = a.window_id
            where a.status not in ('DRAFT', 'PAYMENT_PENDING')""";
    private static final List<String> STATUS_ORDER = List.of("SUBMITTED", "UNDER_REVIEW", "NOT_SELECTED", "SELECTED", "PENDING_VERIFICATION",
            "QUERIED", "NACTVET_REJECTED", "OFFERED", "ACCEPTED", "DECLINED", "OFFER_LAPSED", "WITHDRAWN");

    AdmissionReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        List<String> common = List.of(ACADEMIC_YEAR, INTAKE, WINDOW, AWARD, LEVEL, SEX);
        return List.of(
                new Definition("ADM-01", "Admissions", "Applications by programme",
                        "First choices, eligibility, selection and acceptance against capacity for each programme.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX), this::byProgramme),
                new Definition("ADM-02", "Admissions", "Applications by region and gender", "Where applicants come from.", ROLES, false,
                        common, this::byRegion),
                new Definition("ADM-03", "Admissions", "Applications by entry qualification",
                        "Applicants holding each type of qualification (CSEE, ACSEE, NTA and NVA awards).", ROLES, false, common, this::byQualification),
                new Definition("ADM-04", "Admissions", "Applications by status",
                        "Where every submitted application stands, from review to acceptance.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX), this::byStatus),
                new Definition("ADM-05", "Admissions", "NACTVET verification status",
                        "Selected applicants by programme: awaiting submission, pending, queried, not verified and verified.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX), this::verification),
                new Definition("ADM-06", "Admissions", "Offers and acceptance rate",
                        "Offers made, accepted, declined, lapsed and still open, with the acceptance rate per programme.", ROLES, false,
                        List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX), this::acceptance),
                new Definition("ADM-07", "Admissions", "Admission letters issued", "Every admission letter issued, with edition and status.",
                        ROLES, true, List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX), this::letters),
                new Definition("ADM-08", "Admissions", "Applicant list", "Submitted applications with choices, selection and status.",
                        ROLES, true, List.of(ACADEMIC_YEAR, INTAKE, WINDOW, PROGRAMME, AWARD, LEVEL, SEX, APPLICATION_STATUS), this::applicants));
    }

    private Sql filters(Filter f, String programmeAlias) {
        Sql q = new Sql().eq("w.academic_year_id", "year", f.academicYearId()).eq("w.intake_id", "intake", f.intakeId())
                .eq("a.window_id", "window", f.windowId()).eq("ap.sex", "sex", f.sex());
        if (programmeAlias != null) {
            q.eq(programmeAlias + ".id", "programme", f.programmeId()).eq(programmeAlias + ".award_family", "award", f.awardFamily())
                    .eq(programmeAlias + ".award_level", "level", f.level());
        }
        return q;
    }

    private Data byProgramme(Filter f) {
        Sql q = filters(f, "p");
        List<List<Object>> base = rows("""
                select p.id, p.code, p.name, p.award_family || ' ' || p.award_level,
                       count(*) filter (where c.choice_order = 1),
                       count(*) filter (where c.choice_order = 1 and ap.sex = 'F'),
                       count(*) filter (where c.choice_order = 1 and ap.sex = 'M'),
                       count(*), count(*) filter (where c.eligible)
                from application_choices c
                join applications a on a.id = c.application_id
                join applicants ap on ap.id = a.applicant_id
                join admission_windows w on w.id = a.window_id
                join window_programmes wp on wp.id = c.window_programme_id
                join programmes p on p.id = wp.programme_id
                where a.status not in ('DRAFT', 'PAYMENT_PENDING')""" + q + """
                 group by p.id, p.code, p.name, p.award_family, p.award_level
                 order by p.award_family, p.award_level, p.name""", q);
        Sql cq = new Sql().eq("w.academic_year_id", "year", f.academicYearId()).eq("w.intake_id", "intake", f.intakeId())
                .eq("w.id", "window", f.windowId());
        Map<Long, Long> capacity = new HashMap<>();
        for (List<Object> r : rows("select wp.programme_id, sum(wp.capacity) from window_programmes wp join admission_windows w on w.id = wp.window_id where 1 = 1"
                + cq + " group by wp.programme_id", cq)) capacity.put((Long) r.get(0), num(r.get(1)));
        Sql sq = filters(f, null);
        Map<Long, long[]> placed = new HashMap<>();
        for (List<Object> r : rows("""
                select wp.programme_id, count(*), count(*) filter (where a.status = 'ACCEPTED')
                from applications a join applicants ap on ap.id = a.applicant_id join admission_windows w on w.id = a.window_id
                join window_programmes wp on wp.id = a.selected_window_programme_id
                where a.selected_window_programme_id is not null""" + sq + " group by wp.programme_id", sq)) {
            placed.put((Long) r.get(0), new long[]{num(r.get(1)), num(r.get(2))});
        }
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> r : base) {
            Long id = (Long) r.get(0);
            long cap = capacity.getOrDefault(id, 0L);
            long[] p = placed.getOrDefault(id, new long[2]);
            out.add(new ArrayList<>(List.of(r.get(1), r.get(2), r.get(3), cap, r.get(4), r.get(5), r.get(6), r.get(7), r.get(8), p[0], p[1],
                    ratio(p[1], cap))));
        }
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.text("award", "Award"),
                Column.number("capacity", "Capacity"), Column.number("first", "1st choice"), Column.number("female", "Female"),
                Column.number("male", "Male"), Column.number("choices", "All choices"), Column.number("eligible", "Eligible choices"),
                Column.number("selected", "Selected"), Column.number("accepted", "Accepted"), Column.percent("fill", "Places filled")), out).withTotals();
        long cap = num(d.totals().get(3)), acc = num(d.totals().get(10));
        d.totals().set(11, ratio(acc, cap));
        return d.withNote("Places filled = accepted offers ÷ capacity. An applicant appears once under each programme they chose (1st choice counts each applicant once).");
    }

    private Data byRegion(Filter f) {
        Sql q = filters(f, null);
        List<List<Object>> r = rows("select coalesce(nullif(ap.region, ''), 'Not given'), count(*) filter (where ap.sex = 'F'), count(*) filter (where ap.sex = 'M'), count(*) "
                + BASE + q + " group by 1 order by 4 desc, 1", q);
        long total = r.stream().mapToLong(x -> num(x.get(3))).sum();
        r.forEach(x -> x.add(ratio(x.get(3), total)));
        Data d = new Data(List.of(Column.text("region", "Region"), Column.number("female", "Female"), Column.number("male", "Male"),
                Column.number("total", "Total"), Column.percent("share", "Share")), r).withTotals();
        d.totals().set(4, total == 0 ? null : 1.0);
        return d;
    }

    private Data byQualification(Filter f) {
        Sql q = filters(f, null);
        List<List<Object>> r = rows("""
                select q.type, count(distinct a.id) filter (where ap.sex = 'F'), count(distinct a.id) filter (where ap.sex = 'M'), count(distinct a.id)
                from applications a join applicants ap on ap.id = a.applicant_id join admission_windows w on w.id = a.window_id
                join applicant_qualifications q on q.applicant_id = ap.id
                where a.status not in ('DRAFT', 'PAYMENT_PENDING')""" + q + " group by q.type order by 4 desc", q);
        Map<String, String> names = Map.of("CSEE", "CSEE (Form IV)", "ACSEE", "ACSEE (Form VI)", "NTA", "NTA award", "NVA", "NVA certificate");
        r.forEach(x -> x.set(0, names.getOrDefault(String.valueOf(x.get(0)), String.valueOf(x.get(0)))));
        return new Data(List.of(Column.text("qualification", "Qualification"), Column.number("female", "Female"), Column.number("male", "Male"),
                Column.number("applications", "Applications")), r)
                .withNote("An applicant with several qualifications (e.g. CSEE and an NTA award) is counted under each.");
    }

    private Data byStatus(Filter f) {
        Sql q = filters(f, "p");
        String join = f.programmeId() != null || f.awardFamily() != null || f.level() != null
                ? " join application_choices c1 on c1.application_id = a.id and c1.choice_order = 1 join window_programmes wp1 on wp1.id = c1.window_programme_id join programmes p on p.id = wp1.programme_id"
                : "";
        List<List<Object>> r = rows("select a.status, count(*) filter (where ap.sex = 'F'), count(*) filter (where ap.sex = 'M'), count(*) "
                + BASE.replace("join admission_windows w on w.id = a.window_id", "join admission_windows w on w.id = a.window_id" + join)
                + q + " group by a.status", q);
        r.sort((x, y) -> Integer.compare(order(x.get(0)), order(y.get(0))));
        long total = r.stream().mapToLong(x -> num(x.get(3))).sum();
        r.forEach(x -> {
            x.set(0, label(String.valueOf(x.get(0))));
            x.add(ratio(x.get(3), total));
        });
        Data d = new Data(List.of(Column.text("status", "Status"), Column.number("female", "Female"), Column.number("male", "Male"),
                Column.number("total", "Applications"), Column.percent("share", "Share")), r).withTotals();
        d.totals().set(4, total == 0 ? null : 1.0);
        return join.isEmpty() ? d : d.withNote("Filtered by the programme of the applicant's first choice.");
    }

    private Data verification(Filter f) {
        Sql q = filters(f, "p");
        List<List<Object>> r = rows("""
                select p.code, p.name, count(*),
                       count(*) filter (where a.status = 'SELECTED'),
                       count(*) filter (where a.status = 'PENDING_VERIFICATION'),
                       count(*) filter (where a.status = 'QUERIED'),
                       count(*) filter (where a.status = 'NACTVET_REJECTED'),
                       count(*) filter (where a.status in ('OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED'))
                from applications a join applicants ap on ap.id = a.applicant_id join admission_windows w on w.id = a.window_id
                join window_programmes wp on wp.id = a.selected_window_programme_id join programmes p on p.id = wp.programme_id
                where a.selected_window_programme_id is not null""" + q + " group by p.code, p.name order by p.name", q);
        r.forEach(x -> x.add(ratio(x.get(7), x.get(2))));
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("selected", "Selected"),
                Column.number("notSubmitted", "Not yet submitted"), Column.number("pending", "Pending at NACTVET"), Column.number("queried", "Queried"),
                Column.number("rejected", "Not verified"), Column.number("verified", "Verified"), Column.percent("rate", "Verified %")), r).withTotals();
        d.totals().set(8, ratio(d.totals().get(7), d.totals().get(2)));
        return d;
    }

    private Data acceptance(Filter f) {
        Sql q = filters(f, "p");
        List<List<Object>> r = rows("""
                select p.code, p.name, count(*),
                       count(*) filter (where a.status = 'ACCEPTED'), count(*) filter (where a.status = 'DECLINED'),
                       count(*) filter (where a.status = 'OFFER_LAPSED'), count(*) filter (where a.status = 'OFFERED')
                from applications a join applicants ap on ap.id = a.applicant_id join admission_windows w on w.id = a.window_id
                join window_programmes wp on wp.id = a.selected_window_programme_id join programmes p on p.id = wp.programme_id
                where a.offer_issued_at is not null""" + q + " group by p.code, p.name order by p.name", q);
        r.forEach(x -> x.add(ratio(x.get(3), x.get(2))));
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("offers", "Offers made"),
                Column.number("accepted", "Accepted"), Column.number("declined", "Declined"), Column.number("lapsed", "Lapsed"),
                Column.number("open", "Still open"), Column.percent("rate", "Acceptance rate")), r).withTotals();
        d.totals().set(7, ratio(d.totals().get(3), d.totals().get(2)));
        return d.withNote("Acceptance rate = accepted ÷ offers made (open offers count as not yet accepted).");
    }

    private Data letters(Filter f) {
        Sql q = filters(f, "p");
        List<List<Object>> r = rows("""
                select l.letter_no, concat_ws(' ', ap.first_name, ap.middle_name, ap.surname), a.reference_no, p.name, l.edition, l.status,
                       l.issued_at, l.issued_by_name
                from admission_letters l join applications a on a.id = l.application_id join applicants ap on ap.id = a.applicant_id
                join admission_windows w on w.id = a.window_id
                join window_programmes wp on wp.id = a.selected_window_programme_id join programmes p on p.id = wp.programme_id
                where 1 = 1""" + q + " order by l.issued_at, l.letter_no", q);
        r.forEach(x -> x.set(5, label(String.valueOf(x.get(5)))));
        return new Data(List.of(Column.text("letterNo", "Letter no."), Column.text("name", "Name"), Column.text("ref", "Application"),
                Column.text("programme", "Programme"), Column.number("edition", "Edition"), Column.text("status", "Status"),
                Column.dateTime("issuedAt", "Issued"), Column.text("issuedBy", "Issued by")), r);
    }

    private Data applicants(Filter f) {
        Sql q = filters(f, "p1").eq("a.status", "status", f.status());
        List<List<Object>> r = rows("""
                select a.reference_no, concat_ws(' ', ap.first_name, ap.middle_name, ap.surname), ap.sex, ap.date_of_birth, ap.region, ap.phone,
                       p1.code, ps.code, a.status, a.submitted_at
                from applications a join applicants ap on ap.id = a.applicant_id join admission_windows w on w.id = a.window_id
                left join application_choices c1 on c1.application_id = a.id and c1.choice_order = 1
                left join window_programmes wp1 on wp1.id = c1.window_programme_id left join programmes p1 on p1.id = wp1.programme_id
                left join window_programmes wps on wps.id = a.selected_window_programme_id left join programmes ps on ps.id = wps.programme_id
                where a.status not in ('DRAFT', 'PAYMENT_PENDING')""" + q + " order by a.reference_no", q);
        r.forEach(x -> x.set(8, label(String.valueOf(x.get(8)))));
        return new Data(List.of(Column.text("ref", "Application"), Column.text("name", "Name"), Column.text("sex", "Sex"),
                Column.date("dob", "Date of birth"), Column.text("region", "Region"), Column.text("phone", "Phone"), Column.text("first", "1st choice"),
                Column.text("selected", "Selected for"), Column.text("status", "Status"), Column.dateTime("submitted", "Submitted")), r)
                .withNote(f.programmeId() != null || f.awardFamily() != null || f.level() != null ? "Filtered by the programme of the first choice." : "");
    }

    private static int order(Object status) {
        int i = STATUS_ORDER.indexOf(String.valueOf(status));
        return i < 0 ? 99 : i;
    }

    static String label(String status) {
        return switch (status) {
            case "NACTVET_REJECTED" -> "Not verified by NACTVET";
            case "PENDING_VERIFICATION" -> "Pending NACTVET verification";
            case "OFFER_LAPSED" -> "Offer lapsed";
            case "ACTIVE" -> "Registered";
            default -> status.charAt(0) + status.substring(1).toLowerCase().replace('_', ' ');
        };
    }
}
