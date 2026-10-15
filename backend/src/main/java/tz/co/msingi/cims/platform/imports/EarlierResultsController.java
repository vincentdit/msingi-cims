package tz.co.msingi.cims.platform.imports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Results from before CIMS, brought in by data migration (BR-39), shown with the student's record. */
@Tag(name = "Earlier results")
@RestController
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EarlierResultsController {

    private final JdbcTemplate jdbc;

    public record Module(String code, String title, BigDecimal credits, BigDecimal marks, String grade, BigDecimal points) {
    }

    public record Semester(String academicYear, int semester, int yearOfStudy, BigDecimal gpa, BigDecimal cgpa, String decision, List<Module> modules) {
    }

    @GetMapping("/api/registry/students/{id}/earlier-results")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','EXAMINATIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')")
    public List<Semester> forStudent(@PathVariable Long id) {
        return load(id);
    }

    @Operation(summary = "The signed-in student's results from before CIMS")
    @GetMapping("/api/student/earlier-results")
    @PreAuthorize("hasRole('STUDENT')")
    public List<Semester> mine() {
        List<Long> ids = jdbc.queryForList("select id from students where user_id = ?", Long.class, CurrentUser.requireId());
        return ids.isEmpty() ? List.of() : load(ids.get(0));
    }

    private List<Semester> load(Long studentId) {
        List<Map<String, Object>> sems = jdbc.queryForList("""
                select id, academic_year, semester_no, year_of_study, gpa, cgpa, decision from legacy_semester_results
                where student_id = ? order by academic_year, semester_no""", studentId);
        return sems.stream().map(s -> new Semester((String) s.get("academic_year"), ((Number) s.get("semester_no")).intValue(),
                ((Number) s.get("year_of_study")).intValue(), (BigDecimal) s.get("gpa"), (BigDecimal) s.get("cgpa"), (String) s.get("decision"),
                jdbc.query("select module_code, module_title, credits, marks, grade, points from legacy_module_results where semester_result_id = ? order by module_code",
                        (rs, i) -> new Module(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), rs.getBigDecimal(6)),
                        s.get("id")))).toList();
    }
}
