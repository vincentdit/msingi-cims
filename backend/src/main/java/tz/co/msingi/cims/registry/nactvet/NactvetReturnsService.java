package tz.co.msingi.cims.registry.nactvet;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NACTVET returns in one place (FR-REG-07, FR-EXM-11, FR-GRD-02): for an academic year, where the student registration, the results
 * and the graduand lists stand with NACTVET, and the data problems that would make NACTVET query or reject them, each with how many
 * records it affects and where it is put right. Checking only: the returns themselves are still sent from their own pages.
 */
@Service
@RequiredArgsConstructor
public class NactvetReturnsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record Year(Long id, String code, boolean current) {
    }

    /** The year's new students and their NACTVET registration. */
    public record Registration(long students, long notSent, long submitted, long registered, long queried) {
    }

    public record SheetRow(Long id, String programmeCode, String semester, int yearOfStudy, String status, String nactvetStatus,
                           OffsetDateTime submittedAt) {
    }

    /** The year's main result sheets: published, and what NACTVET has. */
    public record Results(long sheets, long published, long sent, long accepted, long queried, List<SheetRow> rows) {
    }

    public record GraduationRow(Long id, String name, LocalDate ceremonyDate, String status, String nactvetStatus, String nactvetReference, long graduands) {
    }

    /** One data problem: how many records, a few examples, how serious and where to fix it. */
    public record Check(String code, String severity, String title, String fix, String link, long count, List<String> examples) {
    }

    public record Returns(Year year, List<Year> years, String institutionNactvetNo, Registration registration, Results results,
                          List<GraduationRow> graduations, List<Check> checks, long blockers, long warnings) {
    }

    public record Download(String filename, byte[] content) {
    }

    private record Problem(String code, String severity, String title, String fix, String link, String countSql, String listSql) {
    }

    private static final String ENTRY = " from students s join programmes p on p.id = s.programme_id left join applicants a on a.id = s.applicant_id"
            + " where s.status = 'ACTIVE' and s.entry_academic_year_id = ?";
    private static final String CURRENT = " from students s join programmes p on p.id = s.programme_id left join applicants a on a.id = s.applicant_id"
            + " where s.status = 'ACTIVE' and ? is not null";
    private static final String WHO = "select s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), p.code, ";

    /** The problems looked for; each counts records and lists them (registration no., name, programme, detail). */
    private static final List<Problem> PROBLEMS = List.of(
            new Problem("PROGRAMME_CODE", "BLOCKER", "Programmes without their NACTVET code",
                    "Set the NACTVET code of each programme under Academic structure › Programmes (or sync the NACTVET catalogue).", "/staff/programmes",
                    "select count(*) from programmes p where p.active and (p.nactvet_code is null or p.nactvet_code = '') and ? is not null",
                    "select p.code, p.name, p.code, 'No NACTVET code' from programmes p where p.active and (p.nactvet_code is null or p.nactvet_code = '') and ? is not null order by p.code"),
            new Problem("NO_AVN", "BLOCKER", "New students without a NACTVET verification number (AVN)",
                    "Record the AVN NACTVET gave at admission verification on the student's record (Registry › Students), or import it with the students file.",
                    "/staff/students",
                    "select count(*)" + ENTRY + " and (s.nactvet_verification_no is null or s.nactvet_verification_no = '')",
                    WHO + "'No AVN'" + ENTRY + " and (s.nactvet_verification_no is null or s.nactvet_verification_no = '') order by s.registration_no"),
            new Problem("NO_INDEX", "WARNING", "New students without a Form IV index number",
                    "NACTVET matches students by their NECTA index number. Students imported without an application have none: add it on the student's record.",
                    "/staff/students",
                    "select count(*)" + ENTRY + " and (a.necta_index_number is null or a.necta_index_number = '')",
                    WHO + "'No index number'" + ENTRY + " and (a.necta_index_number is null or a.necta_index_number = '') order by s.registration_no"),
            new Problem("REG_QUERIED", "BLOCKER", "Students NACTVET queried at registration",
                    "Open Integrations › NACTVET registration, read NACTVET's reason for each, correct the record and send again.", "/staff/integrations",
                    "select count(*)" + CURRENT + " and s.nactvet_reg_status = 'QUERIED'",
                    WHO + "coalesce((select i.reason from nactvet_registration_items i where i.student_id = s.id and i.status = 'QUERIED' order by i.id desc limit 1), 'Queried')"
                            + CURRENT + " and s.nactvet_reg_status = 'QUERIED' order by s.registration_no"),
            new Problem("NO_PHONE", "WARNING", "Current students without a phone number",
                    "NACTVET and the college reach students by phone; add it on the student's record or ask students to add it in the portal.", "/staff/students",
                    "select count(*)" + CURRENT + " and (s.phone is null or s.phone = '')",
                    WHO + "'No phone'" + CURRENT + " and (s.phone is null or s.phone = '') order by s.registration_no"));

    private static final int EXAMPLES = 5;

    @Transactional(readOnly = true)
    public Returns returns(Long academicYearId) {
        List<Year> years = jdbc.query("select id, code, is_current from academic_years order by start_date desc",
                (rs, i) -> new Year(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
        if (years.isEmpty()) throw new NotFoundException("No academic year has been set up yet");
        Year year = academicYearId == null ? years.stream().filter(Year::current).findFirst().orElse(years.get(0))
                : years.stream().filter(y -> y.id().equals(academicYearId)).findFirst().orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        Long y = year.id();
        String instNo = jdbc.queryForObject("select nactvet_reg_no from tenants where id = cims_current_tenant()", String.class);

        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) as students,
                       count(*) filter (where s.nactvet_reg_status is null) as not_sent,
                       count(*) filter (where s.nactvet_reg_status = 'SUBMITTED') as submitted,
                       count(*) filter (where s.nactvet_reg_status = 'REGISTERED') as registered,
                       count(*) filter (where s.nactvet_reg_status = 'QUERIED') as queried
                from students s where s.status = 'ACTIVE' and s.entry_academic_year_id = ?""", y);
        Registration registration = new Registration(n(r, "students"), n(r, "not_sent"), n(r, "submitted"), n(r, "registered"), n(r, "queried"));

        List<SheetRow> sheets = jdbc.query("""
                select rs.id, p.code, se.name, rs.year_of_study, rs.status,
                       (select n.status from nactvet_result_submissions n where n.result_sheet_id = rs.id order by n.id desc limit 1),
                       (select n.submitted_at from nactvet_result_submissions n where n.result_sheet_id = rs.id order by n.id desc limit 1)
                from result_sheets rs join semesters se on se.id = rs.semester_id join programmes p on p.id = rs.programme_id
                where se.academic_year_id = ? and rs.round = 'MAIN' order by se.semester_no, p.code, rs.year_of_study""",
                (rs, i) -> new SheetRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class)), y);
        Results results = new Results(sheets.size(), sheets.stream().filter(s -> "PUBLISHED".equals(s.status())).count(),
                sheets.stream().filter(s -> s.nactvetStatus() != null).count(), sheets.stream().filter(s -> "ACCEPTED".equals(s.nactvetStatus())).count(),
                sheets.stream().filter(s -> "QUERIED".equals(s.nactvetStatus())).count(), sheets);

        List<GraduationRow> graduations = jdbc.query("""
                select g.id, g.name, g.ceremony_date, g.status, g.nactvet_status, g.nactvet_reference, (select count(*) from graduands gr where gr.graduation_id = g.id)
                from graduations g join academic_years y on y.id = ?
                where coalesce(g.ceremony_date, g.created_at::date) between y.start_date and y.end_date order by coalesce(g.ceremony_date, g.created_at::date)""",
                (rs, i) -> new GraduationRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getLong(7)), y);

        List<Check> checks = new ArrayList<>();
        if (instNo == null || instNo.isBlank()) {
            checks.add(new Check("INSTITUTION_NO", "BLOCKER", "The institution's NACTVET registration number is not recorded",
                    "Ask Msingi support to record it on the institution (it heads every file sent to NACTVET).", null, 1, List.of()));
        }
        for (Problem p : PROBLEMS) {
            Long c = jdbc.queryForObject(p.countSql(), Long.class, y);
            if (c == null || c == 0) continue;
            List<String> ex = jdbc.query(p.listSql() + " limit " + EXAMPLES, (rs, i) -> rs.getString(1) + " · " + rs.getString(2), y);
            checks.add(new Check(p.code(), p.severity(), p.title(), p.fix(), p.link(), c, ex));
        }
        long notSent = sheets.stream().filter(s -> "PUBLISHED".equals(s.status()) && s.nactvetStatus() == null).count();
        if (notSent > 0) {
            checks.add(new Check("RESULTS_NOT_SENT", "WARNING", "Published result sheets not yet sent to NACTVET",
                    "Open each sheet under Examinations › Result sheets, download the NACTVET file and record the submission.", "/staff/results", notSent,
                    sheets.stream().filter(s -> "PUBLISHED".equals(s.status()) && s.nactvetStatus() == null).limit(EXAMPLES)
                            .map(s -> s.programmeCode() + " · " + s.semester() + " · year " + s.yearOfStudy()).toList()));
        }
        if (results.queried() > 0) {
            checks.add(new Check("RESULTS_QUERIED", "BLOCKER", "Result sheets NACTVET queried",
                    "Open the sheet, read NACTVET's note, make any amendment through the amendment process and record the new submission.", "/staff/results",
                    results.queried(), sheets.stream().filter(s -> "QUERIED".equals(s.nactvetStatus())).limit(EXAMPLES)
                            .map(s -> s.programmeCode() + " · " + s.semester() + " · year " + s.yearOfStudy()).toList()));
        }
        List<GraduationRow> gNotSent = graduations.stream().filter(g -> "APPROVED".equals(g.status()) && g.nactvetStatus() == null).toList();
        if (!gNotSent.isEmpty()) {
            checks.add(new Check("GRADUANDS_NOT_SENT", "WARNING", "Approved graduand lists not yet sent to NACTVET",
                    "Open the graduation, download the NACTVET graduand file and record the submission.", "/staff/graduations", gNotSent.size(),
                    gNotSent.stream().limit(EXAMPLES).map(GraduationRow::name).toList()));
        }
        long blockers = checks.stream().filter(c -> "BLOCKER".equals(c.severity())).count();
        return new Returns(year, years, instNo, registration, results, graduations, checks, blockers, checks.size() - blockers);
    }

    /** Every record behind every problem, one sheet, so the registry can work through them. */
    @Transactional
    public Download problems(Long academicYearId) {
        Returns ret = returns(academicYearId);
        Long y = ret.year().id();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("To fix");
            String[] head = {"Problem", "Seriousness", "Registration no. / code", "Name", "Programme", "Detail", "Where to fix"};
            Row h = sh.createRow(0);
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            int[] row = {1};
            for (Problem p : PROBLEMS) {
                jdbc.query(p.listSql(), rs -> {
                    Row x = sh.createRow(row[0]++);
                    x.createCell(0).setCellValue(p.title());
                    x.createCell(1).setCellValue("BLOCKER".equals(p.severity()) ? "Must fix" : "Should fix");
                    for (int c = 1; c <= 4; c++) x.createCell(c + 1).setCellValue(rs.getString(c) == null ? "" : rs.getString(c));
                    x.createCell(6).setCellValue(p.fix());
                }, y);
            }
            for (Check c : ret.checks()) {
                if (PROBLEMS.stream().anyMatch(p -> p.code().equals(c.code()))) continue;
                for (String e : c.examples().isEmpty() ? List.of("") : c.examples()) {
                    Row x = sh.createRow(row[0]++);
                    x.createCell(0).setCellValue(c.title());
                    x.createCell(1).setCellValue("BLOCKER".equals(c.severity()) ? "Must fix" : "Should fix");
                    x.createCell(2).setCellValue(e);
                    x.createCell(6).setCellValue(c.fix());
                }
            }
            for (int i = 0; i < head.length; i++) sh.setColumnWidth(i, i == 6 ? 80 * 256 : 24 * 256);
            sh.createFreezePane(0, 1);
            wb.write(out);
            audit.record("NACTVET_CHECKS_EXPORTED", "AcademicYear", y, Map.of("year", ret.year().code(), "problems", row[0] - 1));
            return new Download("nactvet-checks-" + ret.year().code().replaceAll("[^0-9A-Za-z]+", "-") + ".xlsx", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long n(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? 0 : ((Number) o).longValue();
    }
}
