package tz.co.msingi.cims.reporting;

import tz.co.msingi.cims.platform.security.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The standard report catalogue's building blocks (FR-RPT-02). */
public final class ReportModel {

    private ReportModel() {
    }

    public enum Type { TEXT, NUMBER, MONEY, PERCENT, DATE, DATETIME }

    public record Column(String key, String label, Type type) {
        public static Column text(String key, String label) { return new Column(key, label, Type.TEXT); }
        public static Column number(String key, String label) { return new Column(key, label, Type.NUMBER); }
        public static Column money(String key, String label) { return new Column(key, label, Type.MONEY); }
        public static Column percent(String key, String label) { return new Column(key, label, Type.PERCENT); }
        public static Column date(String key, String label) { return new Column(key, label, Type.DATE); }
        public static Column dateTime(String key, String label) { return new Column(key, label, Type.DATETIME); }
    }

    /**
     * What can narrow a report. Not every report uses every filter: each definition lists the ones it honours.
     * Percentages in rows are fractions (0.45 = 45%).
     */
    public record Filter(Long academicYearId, Long intakeId, Long windowId, Long programmeId, String awardFamily, Integer level, String sex,
                         String status, Integer yearOfStudy, LocalDate from, LocalDate to, String text) {
    }

    /** Filter names, as used by the definitions and the user interface. */
    public static final String ACADEMIC_YEAR = "academicYear", INTAKE = "intake", WINDOW = "window", PROGRAMME = "programme",
            AWARD = "awardFamily", LEVEL = "level", SEX = "sex", APPLICATION_STATUS = "applicationStatus", STUDENT_STATUS = "studentStatus",
            YEAR_OF_STUDY = "yearOfStudy", PERIOD = "period", TEXT = "text";

    public record Data(List<Column> columns, List<List<Object>> rows, List<Object> totals, List<String> notes) {
        public Data(List<Column> columns, List<List<Object>> rows) {
            this(columns, rows, null, List.of());
        }

        /** The same data with a totals row summing the number and money columns (the first column says "Total"). */
        public Data withTotals() {
            List<Object> t = new ArrayList<>();
            for (int c = 0; c < columns.size(); c++) {
                Type type = columns.get(c).type();
                if (type == Type.NUMBER || type == Type.MONEY) {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (List<Object> r : rows) if (r.get(c) instanceof Number n) sum = sum.add(new BigDecimal(n.toString()));
                    t.add(type == Type.NUMBER ? (Object) sum.longValue() : sum);
                } else {
                    t.add(c == 0 ? "Total" : null);
                }
            }
            return new Data(columns, rows, t, notes);
        }

        public Data withNote(String note) {
            if (note == null || note.isBlank()) return this;
            List<String> n = new ArrayList<>(notes);
            n.add(note);
            return new Data(columns, rows, totals, n);
        }
    }

    public record Definition(String code, String area, String title, String description, Set<Role> roles, boolean personalData,
                             List<String> filters, Function<Filter, Data> run) {
    }

    /** A report as generated: what the screen, the Excel file and the PDF show. */
    public record Report(String code, String area, String title, String description, List<String> filterLines, List<Column> columns,
                         List<List<Object>> rows, List<Object> totals, List<String> notes, int rowCount, boolean truncated,
                         String institution, OffsetDateTime generatedAt, String generatedBy) {
    }

    public record CatalogueEntry(String code, String area, String title, String description, boolean personalData, List<String> filters) {
    }
}
