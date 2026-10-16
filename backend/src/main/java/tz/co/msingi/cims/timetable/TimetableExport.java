package tz.co.msingi.cims.timetable;

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
import tz.co.msingi.cims.exams.ExamDtos.Person;
import tz.co.msingi.cims.timetable.TimetableDtos.Cohort;
import tz.co.msingi.cims.timetable.TimetableDtos.ExamTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.Kind;
import tz.co.msingi.cims.timetable.TimetableDtos.OfferingRow;
import tz.co.msingi.cims.timetable.TimetableDtos.SemesterTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingView;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotView;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueRef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** The class or examination timetable of a semester as an Excel workbook, for printing and notice boards. */
@Service
@RequiredArgsConstructor
public class TimetableExport {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy");

    private final TimetableService classes;
    private final ExamTimetableService exams;

    public record Document(String filename, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Document excel(Long semesterId, Kind kind) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 13);
            title.setFont(big);
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            String name;
            if (kind == Kind.CLASS) {
                SemesterTimetable t = classes.semester(semesterId);
                name = t.semesterName();
                Sheet sh = wb.createSheet("Class timetable");
                header(sh, title, head, "Class timetable · " + t.semesterName() + (t.publishedAt() == null ? " · draft (not published)" : ""),
                        new String[]{"Day", "Start", "End", "Module", "Title", "Type", "Group", "Venue", "Lecturer", "Programmes (year)", "Students"});
                Map<Long, OfferingRow> rows = new HashMap<>();
                t.offerings().forEach(o -> rows.put(o.id(), o));
                int ri = 3;
                for (SlotView s : t.slots()) {
                    Row r = sh.createRow(ri++);
                    OfferingRow o = rows.get(s.offeringId());
                    String cohorts = o == null ? "" : String.join(", ", o.cohorts().stream().map(TimetableExport::cohort).toList());
                    Object[] v = {Clashes.DAYS[s.dayOfWeek()], HM.format(s.startTime()), HM.format(s.endTime()), s.moduleCode(), s.moduleTitle(),
                            cap(s.kind()), s.groupLabel(), s.venueCode() + " " + s.venueName(), s.lecturerName(), cohorts, s.students()};
                    fill(r, v);
                }
                widths(sh, 11);
            } else {
                ExamTimetable t = exams.semester(semesterId);
                name = t.semesterName();
                Sheet sh = wb.createSheet("Examination timetable");
                header(sh, title, head, "Examination timetable · " + t.semesterName() + (t.publishedAt() == null ? " · draft (not published)" : ""),
                        new String[]{"Date", "Start", "End", "Module", "Title", "Paper", "Venues", "Candidates", "Seats", "Invigilators"});
                int ri = 3;
                for (SittingView s : t.sittings()) {
                    Row r = sh.createRow(ri++);
                    Object[] v = {DATE.format(s.date()), HM.format(s.startTime()), HM.format(s.endTime()), s.moduleCode(), s.moduleTitle(), s.paper(),
                            String.join(", ", s.venues().stream().map(VenueRef::code).toList()), s.candidates(), s.seats(),
                            String.join(", ", s.invigilators().stream().map(Person::fullName).toList())};
                    fill(r, v);
                }
                widths(sh, 10);
            }
            wb.write(out);
            String file = (kind == Kind.CLASS ? "class-timetable-" : "exam-timetable-") + name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "") + ".xlsx";
            return new Document(file.toLowerCase(), out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void header(Sheet sh, CellStyle title, CellStyle head, String text, String[] cols) {
        sh.createRow(0).createCell(0).setCellValue(text);
        sh.getRow(0).getCell(0).setCellStyle(title);
        Row h = sh.createRow(2);
        for (int i = 0; i < cols.length; i++) {
            h.createCell(i).setCellValue(cols[i]);
            h.getCell(i).setCellStyle(head);
        }
        sh.createFreezePane(0, 3);
    }

    private static void fill(Row r, Object[] v) {
        for (int i = 0; i < v.length; i++) {
            if (v[i] instanceof Number n) r.createCell(i).setCellValue(n.doubleValue());
            else r.createCell(i).setCellValue(v[i] == null ? "" : v[i].toString());
        }
    }

    private static void widths(Sheet sh, int n) {
        for (int i = 0; i < n; i++) sh.autoSizeColumn(i);
    }

    private static String cohort(Cohort c) {
        return c.programmeCode() + " (" + c.yearOfStudy() + ")";
    }

    private static String cap(String kind) {
        return kind.charAt(0) + kind.substring(1).toLowerCase();
    }
}
