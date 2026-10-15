package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.platform.security.DataScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Examination results compared across academic years (FR-EXM-14): pass rates, mean GPA, grade distributions and module
 * performance year on year. Uses confirmed and published results, and results brought in from before CIMS.
 */
@Service
@RequiredArgsConstructor
public class ResultTrendsService {

    private final JdbcTemplate jdbc;
    private final DataScope scope;

    public record YearRow(String academicYear, long students, long passed, BigDecimal passRate, BigDecimal meanGpa, Map<String, Long> decisions,
                          Map<String, Long> grades, boolean beforeCims) {
    }

    public record ModuleTrend(String code, String title, Map<String, BigDecimal> passRateByYear, Map<String, BigDecimal> meanMarkByYear) {
    }

    public record Trends(List<String> years, List<YearRow> rows, List<ModuleTrend> modules) {
    }

    @Transactional(readOnly = true)
    public Trends trends(Long programmeId, Integer yearOfStudy) {
        var sc = scope.current();
        if (programmeId != null && !sc.programme(programmeId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not one of your programmes");
        }
        Collection<Long> allowed = sc.all() ? null : jdbc.queryForList("select id from programmes", Long.class).stream().filter(sc::programme).toList();
        String progFilter = programmeId != null ? " and st.programme_id = " + programmeId
                : allowed == null ? "" : allowed.isEmpty() ? " and false" : " and st.programme_id in (" + String.join(",", allowed.stream().map(String::valueOf).toList()) + ")";
        String yosFilter = yearOfStudy == null ? "" : " and %s = " + yearOfStudy.intValue();

        Map<String, Acc> years = new TreeMap<>();
        // results in CIMS: confirmed or published class sheets, main round
        jdbc.query("""
                select ay.code, r.student_id, r.gpa, r.decision from semester_results r join result_sheets rs on rs.id = r.result_sheet_id
                join semesters se on se.id = rs.semester_id join academic_years ay on ay.id = se.academic_year_id
                join students st on st.id = r.student_id
                where rs.status in ('CONFIRMED', 'PUBLISHED') and rs.round = 'MAIN'""" + progFilter + yosFilter.formatted("rs.year_of_study"),
                rs -> { years.computeIfAbsent(rs.getString(1), k -> new Acc(false)).add(rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)); });
        // results from before CIMS
        jdbc.query("""
                select l.academic_year, l.student_id, l.gpa, upper(coalesce(l.decision, '')) from legacy_semester_results l
                join students st on st.id = l.student_id where true""" + progFilter + yosFilter.formatted("l.year_of_study"),
                rs -> { years.computeIfAbsent(rs.getString(1), k -> new Acc(true)).add(rs.getLong(2), rs.getBigDecimal(3), rs.getString(4)); });
        // grade distribution
        jdbc.query("""
                select ay.code, mr.grade, count(*) from module_results mr join module_offerings mo on mo.id = mr.offering_id
                join semesters se on se.id = mo.semester_id join academic_years ay on ay.id = se.academic_year_id
                join students st on st.id = mr.student_id join module_registrations reg on reg.id = mr.module_registration_id
                where mr.locked and reg.attempt = 'FIRST' and mr.grade is not null""" + progFilter + " group by ay.code, mr.grade",
                rs -> { years.computeIfAbsent(rs.getString(1), k -> new Acc(false)).grades.merge(rs.getString(2), rs.getLong(3), Long::sum); });
        jdbc.query("""
                select l.academic_year, m.grade, count(*) from legacy_module_results m join legacy_semester_results l on l.id = m.semester_result_id
                join students st on st.id = l.student_id where true""" + progFilter + yosFilter.formatted("l.year_of_study") + " group by l.academic_year, m.grade",
                rs -> { years.computeIfAbsent(rs.getString(1), k -> new Acc(true)).grades.merge(rs.getString(2), rs.getLong(3), Long::sum); });

        List<YearRow> rows = years.entrySet().stream().map(e -> e.getValue().row(e.getKey())).toList();

        // module performance year on year (first attempts, confirmed)
        Map<String, ModuleAcc> mods = new TreeMap<>();
        jdbc.query("""
                select m.code, m.title, ay.code, count(*) filter (where mr.remark = 'PASS'), count(*), avg(mr.total)
                from module_results mr join module_offerings mo on mo.id = mr.offering_id join modules m on m.id = mo.module_id
                join semesters se on se.id = mo.semester_id join academic_years ay on ay.id = se.academic_year_id
                join students st on st.id = mr.student_id join module_registrations reg on reg.id = mr.module_registration_id
                where mr.locked and reg.attempt = 'FIRST'""" + progFilter + " group by m.code, m.title, ay.code",
                rs -> {
                    String title = rs.getString(2);
                    ModuleAcc a = mods.computeIfAbsent(rs.getString(1), k -> new ModuleAcc(title));
                    a.pass.put(rs.getString(3), pct(rs.getLong(4), rs.getLong(5)));
                    a.mean.put(rs.getString(3), rs.getBigDecimal(6) == null ? null : rs.getBigDecimal(6).setScale(1, RoundingMode.HALF_UP));
                });
        jdbc.query("""
                select m.module_code, max(m.module_title), l.academic_year,
                       count(*) filter (where upper(m.grade) not in ('F', 'E', 'D', 'I', 'ABS')), count(*), avg(m.marks)
                from legacy_module_results m join legacy_semester_results l on l.id = m.semester_result_id
                join students st on st.id = l.student_id where true""" + progFilter + " group by m.module_code, l.academic_year",
                rs -> {
                    String title = rs.getString(2);
                    ModuleAcc a = mods.computeIfAbsent(rs.getString(1), k -> new ModuleAcc(title));
                    a.pass.putIfAbsent(rs.getString(3), pct(rs.getLong(4), rs.getLong(5)));
                    a.mean.putIfAbsent(rs.getString(3), rs.getBigDecimal(6) == null ? null : rs.getBigDecimal(6).setScale(1, RoundingMode.HALF_UP));
                });
        List<ModuleTrend> modules = mods.entrySet().stream().map(e -> new ModuleTrend(e.getKey(), e.getValue().title, e.getValue().pass, e.getValue().mean)).toList();
        return new Trends(new ArrayList<>(years.keySet()), rows, modules);
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private static final class Acc {
        final boolean legacy;
        final Set<Long> students = new HashSet<>();
        final Set<Long> passed = new HashSet<>();
        final Set<Long> notPassed = new HashSet<>();
        BigDecimal gpaSum = BigDecimal.ZERO;
        long gpaCount;
        final Map<String, Long> decisions = new TreeMap<>();
        final Map<String, Long> grades = new TreeMap<>();

        Acc(boolean legacy) {
            this.legacy = legacy;
        }

        void add(long student, BigDecimal gpa, String decision) {
            students.add(student);
            String d = decision == null || decision.isBlank() ? "UNKNOWN" : decision;
            decisions.merge(d, 1L, Long::sum);
            // passed the year: every semester of the year passed
            if (d.equals("PASS")) passed.add(student);
            else notPassed.add(student);
            if (gpa != null) {
                gpaSum = gpaSum.add(gpa);
                gpaCount++;
            }
        }

        YearRow row(String year) {
            Set<Long> p = new HashSet<>(passed);
            p.removeAll(notPassed);
            return new YearRow(year, students.size(), p.size(), pct(p.size(), students.size()),
                    gpaCount == 0 ? null : gpaSum.divide(BigDecimal.valueOf(gpaCount), 2, RoundingMode.HALF_UP), decisions, grades, legacy);
        }
    }

    private static final class ModuleAcc {
        final String title;
        final Map<String, BigDecimal> pass = new TreeMap<>();
        final Map<String, BigDecimal> mean = new TreeMap<>();

        ModuleAcc(String title) {
            this.title = title;
        }
    }
}
