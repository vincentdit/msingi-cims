package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.SheetModule;
import tz.co.msingi.cims.exams.ExamDtos.SheetRow;
import tz.co.msingi.cims.exams.ExamDtos.SheetView;
import tz.co.msingi.cims.exams.ExamDtos.SubmissionView;
import tz.co.msingi.cims.exams.ExamRepositories.NactvetResultSubmissionRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NACTVET results submission (FR-EXM-11): an Excel file per confirmed result sheet, and a record of each submission and NACTVET's
 * response. The column layout follows the information NACTVET asks institutions for (student identity, verification number,
 * module grades, GPA and remark); it is to be aligned with NACTVET's current template when the institution provides it.
 */
@Service
@RequiredArgsConstructor
public class NactvetResultsService {

    private final ResultSheetService sheets;
    private final NactvetResultSubmissionRepository submissions;
    private final StudentRepository students;
    private final TenantRepository tenants;
    private final ExamAccess access;
    private final AuditService audit;

    public record File(String filename, byte[] content) {
    }

    @Transactional
    public File file(Long sheetId) {
        access.require("export results for NACTVET", ExamAccess.EXAMS_OFFICE);
        ResultSheet s = sheets.require(sheetId);
        if (!ResultSheetService.FINAL.contains(s.getStatus())) {
            throw new BusinessRuleException("NOT_CONFIRMED", "Only results confirmed by the Governing Board can be sent to NACTVET");
        }
        SheetView v = sheets.view(s);
        Map<Long, Student> people = students.findAllById(v.rows().stream().map(SheetRow::studentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Results");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle gpa = wb.createCellStyle();
            gpa.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            String[][] meta = {
                    {"Institution", t.getName()}, {"NACTVET registration no.", nz(t.getNactvetRegNo())},
                    {"Programme", s.getProgramme().getName()}, {"Programme code (NACTVET)", nz(s.getProgramme().getNactvetCode())},
                    {"NTA level", String.valueOf(s.getProgramme().getAwardLevel())}, {"Academic year", s.getSemester().getAcademicYear().getCode()},
                    {"Semester", s.getSemester().getName()}, {"Year of study", String.valueOf(s.getYearOfStudy())},
                    {"Results", s.getRound() == ResultSheet.Round.RESIT ? "After supplementary and special examinations" : "Semester examinations"},
                    {"Confirmed by the Governing Board", s.getConfirmedAt() == null ? "" : s.getConfirmedAt().toLocalDate().toString()}};
            int r = 0;
            for (String[] m : meta) {
                Row row = sh.createRow(r++);
                cell(row, 0, m[0], head);
                row.createCell(1).setCellValue(m[1]);
            }
            r++;
            Row h = sh.createRow(r++);
            String[] fixed = {"S/N", "NACTVET verification no.", "Registration no.", "Surname", "Other names", "Sex"};
            int c = 0;
            for (String f : fixed) cell(h, c++, f, head);
            for (SheetModule m : v.modules()) cell(h, c++, m.code(), head);
            cell(h, c++, "Credits", head);
            cell(h, c++, "GPA", head);
            cell(h, c, "Remark", head);
            int n = 1;
            for (SheetRow sr : v.rows()) {
                Student st = people.get(sr.studentId());
                Row row = sh.createRow(r++);
                int k = 0;
                row.createCell(k++).setCellValue(n++);
                row.createCell(k++).setCellValue(nz(st.getNactvetVerificationNo()));
                row.createCell(k++).setCellValue(st.getRegistrationNo());
                row.createCell(k++).setCellValue(st.getSurname());
                row.createCell(k++).setCellValue(String.join(" ", nz(st.getFirstName()), nz(st.getMiddleName())).trim());
                row.createCell(k++).setCellValue(nz(st.getSex()));
                for (SheetModule m : v.modules()) {
                    var cellv = sr.cells().get(m.code());
                    row.createCell(k++).setCellValue(cellv == null ? "" : cellv.grade() != null ? cellv.grade()
                            : "ABSENT".equals(cellv.remark()) ? "ABS" : "I");
                }
                row.createCell(k++).setCellValue(sr.creditsRegistered().doubleValue());
                Cell g = row.createCell(k++);
                if (sr.gpa() != null) {
                    g.setCellValue(sr.gpa().doubleValue());
                    g.setCellStyle(gpa);
                }
                row.createCell(k).setCellValue(ResultSheetService.decisionText(SemesterResult.Decision.valueOf(sr.decision())));
            }
            for (int i = 0; i <= fixed.length + v.modules().size() + 2; i++) sh.setColumnWidth(i, (i == 0 ? 6 : i < 6 ? 22 : 11) * 256);
            sh.setColumnWidth(0, 30 * 256);
            wb.write(out);
            audit.record("NACTVET_RESULTS_FILE_EXPORTED", "ResultSheet", s.getId(), Map.of("programme", s.getProgramme().getCode(), "students", v.rows().size()));
            String name = ("NACTVET results " + s.getProgramme().getCode() + " Y" + s.getYearOfStudy() + " " + s.getSemester().getName() + " "
                    + s.getSemester().getAcademicYear().getCode().replace('/', '-') + (s.getRound() == ResultSheet.Round.RESIT ? " resits" : "") + ".xlsx")
                    .replaceAll("[^A-Za-z0-9 ._-]", "");
            return new File(name, out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the NACTVET results file", e);
        }
    }

    @Transactional
    public SubmissionView submit(Long sheetId, String reference, String note) {
        access.require("record a NACTVET submission", ExamAccess.EXAMS_OFFICE);
        ResultSheet s = sheets.require(sheetId);
        if (!ResultSheetService.FINAL.contains(s.getStatus())) {
            throw new BusinessRuleException("NOT_CONFIRMED", "Only results confirmed by the Governing Board can be sent to NACTVET");
        }
        NactvetResultSubmission x = new NactvetResultSubmission();
        x.setSheet(s);
        x.setReference(blank(reference));
        x.setNote(blank(note));
        x.setStudents(s.getResults().size());
        x.setSubmittedByName(CurrentUser.name());
        submissions.save(x);
        audit.record("NACTVET_RESULTS_SUBMITTED", "ResultSheet", s.getId(), Map.of("reference", String.valueOf(x.getReference()), "students", x.getStudents()));
        return view(x);
    }

    @Transactional
    public SubmissionView respond(Long submissionId, NactvetResultSubmission.Status status, String note) {
        access.require("record NACTVET's response", ExamAccess.EXAMS_OFFICE);
        NactvetResultSubmission x = submissions.findById(submissionId).orElseThrow(() -> new NotFoundException("Submission", submissionId));
        if (status == NactvetResultSubmission.Status.SUBMITTED) throw new BusinessRuleException("INVALID_STATUS", "Record NACTVET's acceptance or query");
        x.setStatus(status);
        if (note != null && !note.isBlank()) x.setNote(note.trim());
        x.setResponseByName(CurrentUser.name());
        x.setResponseAt(OffsetDateTime.now());
        audit.record("NACTVET_RESULTS_RESPONSE", "ResultSheet", x.getSheet().getId(), Map.of("status", status.name(), "note", String.valueOf(note)));
        return view(x);
    }

    static SubmissionView view(NactvetResultSubmission x) {
        return new SubmissionView(x.getId(), x.getStatus().name(), x.getReference(), x.getNote(), x.getStudents(), x.getSubmittedByName(),
                x.getSubmittedAt(), x.getResponseByName(), x.getResponseAt());
    }

    private static void cell(Row r, int i, String v, CellStyle style) {
        Cell c = r.createCell(i);
        c.setCellValue(v);
        c.setCellStyle(style);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
