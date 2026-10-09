package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static tz.co.msingi.cims.reporting.ReportModel.PROGRAMME;
import static tz.co.msingi.cims.reporting.ReportModel.SEMESTER;
import static tz.co.msingi.cims.reporting.ReportModel.YEAR_OF_STUDY;

/** Examination reports (FR-EXM-14, BP-08 step 7): module performance, the class result sheet and results by programme. */
@Component
class ExamReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.HEAD_OF_DEPARTMENT, Role.EXAMINATIONS_OFFICER,
            Role.MANAGEMENT_VIEWER, Role.AUDITOR);
    private static final Map<String, String> DECISIONS = new LinkedHashMap<>();

    static {
        DECISIONS.put("PASS", "Pass");
        DECISIONS.put("SUPPLEMENTARY", "Supplementary");
        DECISIONS.put("SPECIAL_EXAM", "Special exam");
        DECISIONS.put("INCOMPLETE", "Incomplete");
        DECISIONS.put("REPEAT", "Repeat");
        DECISIONS.put("DISCONTINUE", "Discontinue");
    }

    ExamReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("EXM-01", "Examinations", "Module performance",
                        "Per module in a semester: students, average mark, pass rate, grade distribution and where the results are in approval.",
                        ROLES, false, List.of(SEMESTER), this::modulePerformance),
                new Definition("EXM-02", "Examinations", "Class result sheet",
                        "Grades of every student of a programme and year in each module, with GPA, CGPA, decision and classification.",
                        ROLES, true, List.of(SEMESTER, PROGRAMME, YEAR_OF_STUDY), this::classSheet),
                new Definition("EXM-03", "Examinations", "Results by programme",
                        "Per class result sheet: stage, students, decisions, pass rate and average GPA.", ROLES, false, List.of(SEMESTER, PROGRAMME),
                        this::byProgramme));
    }

    private Data modulePerformance(Filter f) {
        if (f.semesterId() == null) return new Data(List.of(Column.text("note", "Choose a semester")), List.of()).withNote("Choose the semester to report on.");
        Sql q = new Sql().param("sem", f.semesterId());
        List<List<Object>> r = rows("""
                select m.code, m.title, o.status, count(r.id),
                       count(r.id) filter (where r.remark in ('PASS', 'FAIL')), round(avg(r.total), 2),
                       count(r.id) filter (where r.remark = 'PASS'), count(r.id) filter (where r.remark = 'FAIL'),
                       count(r.id) filter (where r.remark = 'ABSENT'), count(r.id) filter (where r.remark = 'INCOMPLETE'),
                       (select string_agg(g.grade || ' ' || g.n, ' · ' order by g.top desc) from
                           (select r2.grade, count(*) n, max(r2.total) top from module_results r2 where r2.offering_id = o.id and r2.grade is not null
                            group by r2.grade) g)
                from module_offerings o join modules m on m.id = o.module_id left join module_results r on r.offering_id = o.id
                where o.semester_id = :sem group by o.id, m.code, m.title, o.status order by m.code""", q);
        r.forEach(x -> {
            x.set(2, stage(String.valueOf(x.get(2))));
            x.add(6, ratio(x.get(6), x.get(4)));
        });
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("module", "Module"), Column.text("stage", "Stage"),
                Column.number("students", "Students"), Column.number("graded", "Graded"), Column.decimal("mean", "Average mark"),
                Column.percent("passRate", "Pass %"), Column.number("passed", "Passed"), Column.number("failed", "Failed"),
                Column.number("absent", "Absent"), Column.number("incomplete", "Incomplete"), Column.text("grades", "Grades")), r);
        return d.withNote("Pass % is of graded students (absent and incomplete excluded).");
    }

    private Data classSheet(Filter f) {
        if (f.semesterId() == null || f.programmeId() == null || f.yearOfStudy() == null) {
            return new Data(List.of(Column.text("note", "Choose a semester, programme and year")), List.of())
                    .withNote("Choose the semester, the programme and the year of study.");
        }
        Sql q = new Sql().param("sem", f.semesterId()).param("prog", f.programmeId()).param("year", f.yearOfStudy());
        List<List<Object>> head = rows("""
                select rs.id, rs.status, reg.name from result_sheets rs join regulation_sets reg on reg.id = rs.regulation_set_id
                where rs.semester_id = :sem and rs.programme_id = :prog and rs.year_of_study = :year and rs.round = 'MAIN'""", q);
        if (head.isEmpty()) {
            return new Data(List.of(Column.text("note", "Not computed")), List.of())
                    .withNote("The Examinations Officer has not yet computed results for this class.");
        }
        q.param("sheet", head.get(0).get(0));
        List<List<Object>> grades = rows("""
                select sr.student_id, m.code, coalesce(r.grade, case r.remark when 'ABSENT' then 'ABS' else 'I' end)
                from semester_results sr join semester_registrations reg on reg.student_id = sr.student_id and reg.semester_id = :sem
                join module_registrations mr on mr.semester_registration_id = reg.id join modules m on m.id = mr.module_id
                left join module_results r on r.module_registration_id = mr.id
                where sr.result_sheet_id = :sheet""", q);
        TreeSet<String> codes = new TreeSet<>();
        Map<Long, Map<String, Object>> byStudent = new LinkedHashMap<>();
        for (List<Object> g : grades) {
            codes.add(String.valueOf(g.get(1)));
            byStudent.computeIfAbsent(num(g.get(0)), k -> new LinkedHashMap<>()).put(String.valueOf(g.get(1)), g.get(2));
        }
        List<List<Object>> students = rows("""
                select sr.student_id, s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex,
                       sr.credits_passed, sr.credits_registered, sr.gpa, sr.cgpa, sr.decision, sr.classification
                from semester_results sr join students s on s.id = sr.student_id where sr.result_sheet_id = :sheet
                order by s.registration_no""", q);
        List<Column> cols = new ArrayList<>(List.of(Column.text("regNo", "Registration no."), Column.text("surname", "Surname"),
                Column.text("names", "Other names"), Column.text("sex", "Sex")));
        codes.forEach(c -> cols.add(Column.text("m_" + c, c)));
        cols.addAll(List.of(Column.number("creditsPassed", "Credits passed"), Column.number("credits", "Credits"), Column.decimal("gpa", "GPA"),
                Column.decimal("cgpa", "CGPA"), Column.text("decision", "Decision"), Column.text("class", "Classification")));
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> s : students) {
            List<Object> row = new ArrayList<>(s.subList(1, 5));
            Map<String, Object> g = byStudent.getOrDefault(num(s.get(0)), Map.of());
            codes.forEach(c -> row.add(g.getOrDefault(c, "")));
            row.add(s.get(5));
            row.add(s.get(6));
            row.add(s.get(7));
            row.add(s.get(8));
            row.add(DECISIONS.getOrDefault(String.valueOf(s.get(9)), String.valueOf(s.get(9))));
            row.add(s.get(10));
            out.add(row);
        }
        return new Data(cols, out).withNote("Stage: " + sheetStage(String.valueOf(head.get(0).get(1))) + ". Regulations: " + head.get(0).get(2)
                + ". I = incomplete, ABS = absent from the examination.");
    }

    private Data byProgramme(Filter f) {
        if (f.semesterId() == null) return new Data(List.of(Column.text("note", "Choose a semester")), List.of()).withNote("Choose the semester to report on.");
        Sql q = new Sql().param("sem", f.semesterId()).eq("p.id", "programme", f.programmeId());
        List<List<Object>> r = rows("""
                select p.code, p.name, rs.year_of_study, case rs.round when 'RESIT' then 'After resits' else 'Main' end, rs.status, count(sr.id),
                       count(sr.id) filter (where sr.decision = 'PASS'), count(sr.id) filter (where sr.decision = 'SUPPLEMENTARY'),
                       count(sr.id) filter (where sr.decision = 'SPECIAL_EXAM'), count(sr.id) filter (where sr.decision in ('REPEAT', 'DISCONTINUE', 'CARRY_OVER')),
                       round(avg(sr.gpa), 2)
                from result_sheets rs join programmes p on p.id = rs.programme_id left join semester_results sr on sr.result_sheet_id = rs.id
                where rs.semester_id = :sem""" + q + " group by p.code, p.name, rs.year_of_study, rs.round, rs.status order by p.code, rs.year_of_study, rs.round", q);
        r.forEach(x -> {
            x.set(4, sheetStage(String.valueOf(x.get(4))));
            x.add(10, ratio(x.get(6), x.get(5)));
        });
        Data d = new Data(List.of(Column.text("code", "Code"), Column.text("programme", "Programme"), Column.number("year", "Year"),
                Column.text("round", "Results"), Column.text("stage", "Stage"), Column.number("students", "Students"), Column.number("pass", "Pass"),
                Column.number("supp", "Supplementary"), Column.number("special", "Special exam"), Column.number("repeat", "Carry over, repeat or discontinue"),
                Column.percent("passRate", "Pass %"), Column.decimal("gpa", "Average GPA")), r);
        return d;
    }

    static String stage(String s) {
        return switch (s) {
            case "DRAFT" -> "With lecturer";
            case "SUBMITTED" -> "With Head of Department";
            case "HOD_APPROVED" -> "With Department Board";
            case "BOARD_APPROVED" -> "Department approved";
            default -> s;
        };
    }

    static String sheetStage(String s) {
        return switch (s) {
            case "COMPUTED" -> "With Academic Committee";
            case "RECOMMENDED" -> "With Governing Board";
            case "CONFIRMED" -> "Confirmed, not yet published";
            case "PUBLISHED" -> "Published";
            default -> s;
        };
    }
}
