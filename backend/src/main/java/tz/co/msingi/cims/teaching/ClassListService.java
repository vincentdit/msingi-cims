package tz.co.msingi.cims.teaching;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.teaching.TeachingDtos.AttendanceView;
import tz.co.msingi.cims.teaching.TeachingDtos.StudentAttendance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A module's class list (FR-TCH-02, BRU-07: approved registrations only) as a printable PDF that doubles as a paper attendance register
 * for sessions held without a device, and as an Excel sheet with each student's attendance so far. For the module's lecturers and
 * the people who oversee it.
 */
@Service
@RequiredArgsConstructor
public class ClassListService {

    public record Document(String filename, String contentType, byte[] bytes) {
    }

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/class-list.html");
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int BLANK_SESSIONS = 10;

    private final AttendanceService attendance;
    private final TenantRepository tenants;
    private final AuditService audit;

    private record Line(int no, Student student, StudentAttendance tally) {
    }

    private record Data(ModuleOffering offering, AttendanceView view, List<Line> lines) {
    }

    private Data data(Long offeringId) {
        AttendanceView v = attendance.view(offeringId);   // checks the caller may see this module
        ModuleOffering o = attendance.offering(offeringId);
        Map<Long, StudentAttendance> tallies = v.students().stream().collect(Collectors.toMap(StudentAttendance::moduleRegistrationId, Function.identity()));
        List<ModuleRegistration> regs = attendance.classList(o);
        List<Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < regs.size(); i++) lines.add(new Line(i + 1, regs.get(i).getRegistration().getStudent(), tallies.get(regs.get(i).getId())));
        return new Data(o, v, lines);
    }

    @Transactional
    public Document pdf(Long offeringId) {
        Data d = data(offeringId);
        ModuleOffering o = d.offering();
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        String colour = String.valueOf(t.getBranding().getOrDefault("primaryColor", ""));
        StringBuilder heads = new StringBuilder(), rows = new StringBuilder();
        for (int i = 0; i < BLANK_SESSIONS; i++) heads.append("<th class=\"d\">__/__</th>");
        for (Line l : d.lines()) {
            Student s = l.student();
            StudentAttendance a = l.tally();
            rows.append("<tr><td class=\"r\">").append(l.no()).append("</td><td>").append(e(s.getRegistrationNo())).append("</td><td>").append(e(s.fullName()))
                    .append("</td><td class=\"c\">").append(e(sex(s.getSex()))).append("</td><td>").append(e(s.getProgramme().getCode())).append("</td>")
                    .append("<td class=\"r").append(a != null && a.below() ? " warn" : "").append("\">").append(e(a == null ? "—" : AttendanceService.pct(a.percent())))
                    .append("</td>");
            for (int i = 0; i < BLANK_SESSIONS; i++) rows.append("<td class=\"d\"></td>");
            rows.append("</tr>");
        }
        if (d.lines().isEmpty()) rows.append("<tr><td colspan=\"").append(6 + BLANK_SESSIONS).append("\">No students registered for this module.</td></tr>");
        Map<String, String> values = new HashMap<>();
        values.put("title", "Class list and attendance register");
        values.put("institution", t.getName());
        values.put("primaryColor", HEX_COLOUR.matcher(colour).matches() ? colour : "#1F4E8C");
        values.put("moduleCode", o.getModule().getCode());
        values.put("moduleTitle", o.getModule().getTitle());
        values.put("credits", o.getModule().getCredits() == null ? "—" : o.getModule().getCredits().stripTrailingZeros().toPlainString());
        values.put("semester", o.getSemester().getName());
        values.put("academicYear", o.getSemester().getAcademicYear() == null ? "" : o.getSemester().getAcademicYear().getCode());
        values.put("lecturers", d.view().lecturers().isEmpty() ? "not allocated" : String.join(", ", d.view().lecturers()));
        values.put("students", String.valueOf(d.lines().size()));
        values.put("sessions", d.view().sessions() + (d.view().sessions() == 1 ? " session" : " sessions"));
        values.put("threshold", AttendanceService.pct(d.view().threshold()));
        values.put("printed", DATE.format(LocalDate.now(AttendanceService.ZONE)));
        String dept = d.view().departmentName() == null ? "" : " · " + e(d.view().departmentName());
        byte[] pdf = PdfRenderer.render(TEMPLATE.render(values, Map.of("department", dept, "dateHeads", heads.toString(), "rows", rows.toString())),
                "class list");
        audit.record("CLASS_LIST_PRINTED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "format", "PDF", "students", d.lines().size()));
        return new Document(filename(o, "pdf"), "application/pdf", pdf);
    }

    @Transactional
    public Document excel(Long offeringId) {
        Data d = data(offeringId);
        ModuleOffering o = d.offering();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Class list");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 13);
            title.setFont(big);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.0"));

            Row r0 = sh.createRow(0);
            r0.createCell(0).setCellValue(o.getModule().getCode() + " " + o.getModule().getTitle() + " · class list");
            r0.getCell(0).setCellStyle(title);
            sh.createRow(1).createCell(0).setCellValue(o.getSemester().getName()
                    + (o.getSemester().getAcademicYear() == null ? "" : " " + o.getSemester().getAcademicYear().getCode())
                    + " · Lecturer(s): " + (d.view().lecturers().isEmpty() ? "not allocated" : String.join(", ", d.view().lecturers()))
                    + " · " + d.view().sessions() + (d.view().sessions() == 1 ? " session" : " sessions") + " recorded · threshold " + AttendanceService.pct(d.view().threshold()));
            String[] cols = {"No.", "Registration no.", "Name", "Sex", "Programme", "Sessions counted", "Attended", "Absent", "Excused", "Attendance %",
                    "Below threshold", "Examination eligibility"};
            Row h = sh.createRow(3);
            for (int i = 0; i < cols.length; i++) {
                h.createCell(i).setCellValue(cols[i]);
                h.getCell(i).setCellStyle(head);
            }
            int ri = 4;
            for (Line l : d.lines()) {
                Row r = sh.createRow(ri++);
                Student s = l.student();
                StudentAttendance a = l.tally();
                r.createCell(0).setCellValue(l.no());
                r.createCell(1).setCellValue(s.getRegistrationNo());
                r.createCell(2).setCellValue(s.fullName());
                r.createCell(3).setCellValue(sex(s.getSex()));
                r.createCell(4).setCellValue(s.getProgramme().getCode());
                if (a != null) {
                    r.createCell(5).setCellValue(a.counted());
                    r.createCell(6).setCellValue(a.attended());
                    r.createCell(7).setCellValue(a.absent());
                    r.createCell(8).setCellValue(a.excused());
                    if (a.percent() != null) {
                        r.createCell(9).setCellValue(a.percent().doubleValue());
                        r.getCell(9).setCellStyle(pct);
                    }
                    r.createCell(10).setCellValue(a.below() ? "Yes" : "No");
                    r.createCell(11).setCellValue(a.eligible() == null ? "No list yet" : a.eligible() ? (a.exempted() ? "Exempted" : "Eligible") : "Not eligible");
                }
            }
            int[] widths = {6, 22, 32, 6, 12, 10, 10, 10, 10, 12, 10, 18};
            for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
            sh.createFreezePane(0, 4);
            wb.write(out);
            audit.record("CLASS_LIST_PRINTED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "format", "XLSX", "students", d.lines().size()));
            return new Document(filename(o, "xlsx"), XLSX, out.toByteArray());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String filename(ModuleOffering o, String ext) {
        return ("class-list-" + o.getModule().getCode() + "-" + o.getSemester().getName()).replaceAll("[^A-Za-z0-9-]+", "-") + "." + ext;
    }

    private static String sex(String s) {
        if (s == null || s.isBlank()) return "";
        return s.substring(0, 1).toUpperCase();
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }
}
