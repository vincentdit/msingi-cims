package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The institution overview for the Principal and management (FR-RPT-01): for one academic year, how many students the
 * institution has and is taking in against its accredited places, how much of the year's fees has been collected, how
 * students are passing, and how much demand there is for each programme, by programme, with the programmes that need
 * attention picked out against stated thresholds. Read-only and institution-wide.
 */
@Service
public class OverviewService {

    /** The thresholds behind "needs attention" (shown on the page so the reader knows the rule). */
    public static final double LOW_INTAKE = 0.5, LOW_COLLECTION = 0.6, LOW_PASS = 0.6, LOW_DEMAND = 1.0;

    private final NamedParameterJdbcTemplate jdbc;

    public OverviewService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Year(Long id, String code, boolean current) {
    }

    public record Totals(long students, long female, long male, long newStudents, long places, Double intakeRate, BigDecimal billed,
                         BigDecimal collected, BigDecimal outstanding, Double collectionRate, long assessed, long passed, Double passRate,
                         long applicants, long windowPlaces, Double applicantsPerPlace, String windowName) {
    }

    public record ProgrammeRow(Long id, String code, String name, String award, long students, long female, long male, long newStudents,
                               Integer places, Double intakeRate, BigDecimal billed, BigDecimal collected, BigDecimal outstanding,
                               Double collectionRate, long assessed, long passed, Double passRate, long applicants, long windowPlaces,
                               Double applicantsPerPlace, List<String> attention) {
    }

    public record Point(String key, String label, BigDecimal value) {
    }

    public record Thresholds(double lowIntake, double lowCollection, double lowPass, double lowDemand) {
    }

    public record Overview(Year year, List<Year> years, Totals totals, List<ProgrammeRow> programmes, List<Point> intakeByYear,
                           List<Point> collectionsByMonth, Thresholds thresholds) {
    }

    @Transactional(readOnly = true)
    public Overview overview(Long academicYearId) {
        List<Year> years = jdbc.query("select id, code, is_current from academic_years order by start_date desc", Map.of(),
                (rs, i) -> new Year(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
        if (years.isEmpty()) {
            return new Overview(null, List.of(), emptyTotals(), List.of(), List.of(), List.of(), thresholds());
        }
        Year year = academicYearId == null ? years.stream().filter(Year::current).findFirst().orElse(years.get(0))
                : years.stream().filter(y -> y.id().equals(academicYearId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        MapSqlParameterSource p = new MapSqlParameterSource("y", year.id());

        Map<Long, long[]> students = new HashMap<>(); // current, female, male, new this year
        jdbc.query("""
                select programme_id,
                       count(*) filter (where status in ('ADMITTED', 'ACTIVE')),
                       count(*) filter (where status in ('ADMITTED', 'ACTIVE') and sex = 'F'),
                       count(*) filter (where status in ('ADMITTED', 'ACTIVE') and sex = 'M'),
                       count(*) filter (where entry_academic_year_id = :y)
                from students group by programme_id""", p,
                rs -> { students.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}); });

        Map<Long, BigDecimal[]> fees = new HashMap<>(); // billed, collected
        jdbc.query("""
                select s.programme_id, coalesce(sum(i.total_amount), 0), coalesce(sum(i.paid_amount), 0)
                from invoices i join students s on i.payer_type = 'STUDENT' and s.id = i.payer_id
                where i.purpose = 'ANNUAL_FEES' and i.status not in ('CANCELLED', 'CREDITED') and i.academic_year_id = :y
                group by s.programme_id""", p,
                rs -> { fees.put(rs.getLong(1), new BigDecimal[]{rs.getBigDecimal(2), rs.getBigDecimal(3)}); });

        Map<Long, long[]> results = new HashMap<>(); // assessed, passed (main results published in the year)
        jdbc.query("""
                select rs.programme_id, count(sr.id), count(sr.id) filter (where sr.decision = 'PASS')
                from result_sheets rs join semesters se on se.id = rs.semester_id join semester_results sr on sr.result_sheet_id = rs.id
                where se.academic_year_id = :y and rs.status = 'PUBLISHED' and rs.round = 'MAIN'
                group by rs.programme_id""", p,
                rs -> { results.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)}); });

        // demand: the year's latest admission window (submitted applicants with the programme as first choice, against its places)
        List<Map<String, Object>> w = jdbc.queryForList("""
                select id, name from admission_windows where academic_year_id = :y and status <> 'DRAFT'
                order by opens_at desc limit 1""", p);
        Map<Long, long[]> demand = new HashMap<>(); // applicants, places
        String windowName = null;
        if (!w.isEmpty()) {
            windowName = (String) w.get(0).get("name");
            jdbc.query("""
                    select wp.programme_id, wp.capacity,
                           (select count(*) from application_choices c join applications a on a.id = c.application_id
                            where c.window_programme_id = wp.id and c.choice_order = 1 and a.status not in ('DRAFT', 'PAYMENT_PENDING'))
                    from window_programmes wp where wp.window_id = :w""",
                    new MapSqlParameterSource("w", ((Number) w.get(0).get("id")).longValue()),
                    rs -> { demand.put(rs.getLong(1), new long[]{rs.getLong(3), rs.getLong(2)}); });
        }

        List<ProgrammeRow> rows = jdbc.query("""
                select id, code, name, award_name, accredited_capacity from programmes where active order by award_family, award_level, name""",
                Map.of(), (rs, i) -> {
                    long id = rs.getLong(1);
                    Integer places = (Integer) rs.getObject(5);
                    long[] st = students.getOrDefault(id, new long[4]);
                    BigDecimal[] fe = fees.getOrDefault(id, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    long[] re = results.getOrDefault(id, new long[2]);
                    long[] de = demand.getOrDefault(id, new long[2]);
                    Double intake = places == null || places == 0 ? null : (double) st[3] / places;
                    Double collection = rate(fe[1], fe[0]);
                    Double pass = re[0] == 0 ? null : (double) re[1] / re[0];
                    Double perPlace = de[1] == 0 ? null : (double) de[0] / de[1];
                    List<String> attention = new ArrayList<>();
                    if (intake != null && intake < LOW_INTAKE) attention.add("Intake below " + pct(LOW_INTAKE) + " of places");
                    if (collection != null && collection < LOW_COLLECTION) attention.add("Fees collected below " + pct(LOW_COLLECTION));
                    if (pass != null && pass < LOW_PASS) attention.add("Pass rate below " + pct(LOW_PASS));
                    if (perPlace != null && perPlace < LOW_DEMAND) attention.add("Fewer applicants than places");
                    return new ProgrammeRow(id, rs.getString(2), rs.getString(3), rs.getString(4), st[0], st[1], st[2], st[3], places, intake,
                            fe[0], fe[1], fe[0].subtract(fe[1]), collection, re[0], re[1], pass, de[0], de[1], perPlace, attention);
                });

        long cur = 0, f = 0, m = 0, fresh = 0, places = 0, assessed = 0, passed = 0, applicants = 0, windowPlaces = 0;
        BigDecimal billed = BigDecimal.ZERO, collected = BigDecimal.ZERO;
        for (ProgrammeRow r : rows) {
            cur += r.students(); f += r.female(); m += r.male(); fresh += r.newStudents();
            places += r.places() == null ? 0 : r.places();
            billed = billed.add(r.billed()); collected = collected.add(r.collected());
            assessed += r.assessed(); passed += r.passed(); applicants += r.applicants(); windowPlaces += r.windowPlaces();
        }
        Totals totals = new Totals(cur, f, m, fresh, places, places == 0 ? null : (double) fresh / places, billed, collected,
                billed.subtract(collected), rate(collected, billed), assessed, passed, assessed == 0 ? null : (double) passed / assessed,
                applicants, windowPlaces, windowPlaces == 0 ? null : (double) applicants / windowPlaces, windowName);

        // new students by year of entry (the five most recent years, oldest first)
        Map<String, BigDecimal> perYear = new LinkedHashMap<>();
        List<Year> recent = years.subList(0, Math.min(5, years.size()));
        for (int i = recent.size() - 1; i >= 0; i--) perYear.put(recent.get(i).code(), BigDecimal.ZERO);
        jdbc.query("select y.code, count(*) from students s join academic_years y on y.id = s.entry_academic_year_id group by y.code", Map.of(),
                rs -> { if (perYear.containsKey(rs.getString(1))) perYear.put(rs.getString(1), BigDecimal.valueOf(rs.getLong(2))); });
        List<Point> intakeByYear = perYear.entrySet().stream().map(e -> new Point(e.getKey(), e.getKey(), e.getValue())).toList();

        // fees and other payments received in the last twelve months
        YearMonth now = YearMonth.now(Sql.ZONE);
        Map<YearMonth, BigDecimal> perMonth = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) perMonth.put(now.minusMonths(i), BigDecimal.ZERO);
        jdbc.query("""
                select extract(year from paid_at at time zone 'Africa/Dar_es_Salaam'), extract(month from paid_at at time zone 'Africa/Dar_es_Salaam'),
                       sum(amount) from payments where paid_at >= :since group by 1, 2""",
                new MapSqlParameterSource("since", now.minusMonths(11).atDay(1).atStartOfDay(Sql.ZONE).toOffsetDateTime()),
                rs -> { perMonth.put(YearMonth.of(rs.getInt(1), rs.getInt(2)), rs.getBigDecimal(3)); });
        List<Point> collections = perMonth.entrySet().stream().map(e -> new Point(e.getKey().toString(),
                e.getKey().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + (e.getKey().getMonthValue() == 1 ? " " + e.getKey().getYear() : ""),
                e.getValue())).toList();

        return new Overview(year, years, totals, rows, intakeByYear, collections, thresholds());
    }

    private static Thresholds thresholds() {
        return new Thresholds(LOW_INTAKE, LOW_COLLECTION, LOW_PASS, LOW_DEMAND);
    }

    private static Totals emptyTotals() {
        return new Totals(0, 0, 0, 0, 0, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, 0, null, 0, 0, null, null);
    }

    private static Double rate(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.divide(whole, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String pct(double d) {
        return Math.round(d * 100) + "%";
    }
}
