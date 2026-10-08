package tz.co.msingi.cims.reporting;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Report;
import tz.co.msingi.cims.reporting.ReportModel.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Excel and PDF versions of a report (FR-RPT-02), with the institution, filters and who generated it. */
final class ReportExporter {

    /** PDF is for printing and signing: very long lists are cut here and the Excel file has them all. */
    static final int PDF_MAX_ROWS = 3000;
    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/report.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private ReportExporter() {
    }

    // ------------------------------------------------------------------ Excel

    static byte[] excel(Report r) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SXSSFSheet sh = wb.createSheet(sheetName(r.code() + " " + r.title()));
            sh.trackAllColumnsForAutoSizing();
            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            CellStyle title = wb.createCellStyle();
            title.setFont(titleFont);
            CellStyle muted = wb.createCellStyle();
            Font grey = wb.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            muted.setFont(grey);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setWrapText(true);

            Map<Type, CellStyle> body = styles(wb, null);
            Map<Type, CellStyle> totals = styles(wb, bold);

            int row = 0;
            cell(sh.createRow(row++), 0, r.title(), title);
            cell(sh.createRow(row++), 0, r.institution(), null);
            for (String f : r.filterLines()) cell(sh.createRow(row++), 0, f, muted);
            cell(sh.createRow(row++), 0, "Generated " + DATE_TIME.format(r.generatedAt().atZoneSameInstant(Sql.ZONE)) + " by " + r.generatedBy(), muted);
            row++;
            int headerRow = row;
            Row h = sh.createRow(row++);
            for (int c = 0; c < r.columns().size(); c++) cell(h, c, r.columns().get(c).label(), header);
            for (List<Object> values : r.rows()) write(sh.createRow(row++), r.columns(), values, body);
            if (r.totals() != null) write(sh.createRow(row++), r.columns(), r.totals(), totals);
            for (String n : r.notes()) cell(sh.createRow(++row), 0, n, muted);

            sh.createFreezePane(0, headerRow + 1);
            if (!r.rows().isEmpty()) {
                sh.setAutoFilter(new CellRangeAddress(headerRow, headerRow + r.rows().size(), 0, r.columns().size() - 1));
            }
            for (int c = 0; c < r.columns().size(); c++) {
                sh.autoSizeColumn(c);
                sh.setColumnWidth(c, Math.min(Math.max(sh.getColumnWidth(c), 10 * 256), 60 * 256));
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<Type, CellStyle> styles(SXSSFWorkbook wb, Font font) {
        Map<Type, CellStyle> m = new EnumMap<>(Type.class);
        Map<Type, String> formats = Map.of(Type.NUMBER, "#,##0", Type.MONEY, "#,##0.00", Type.PERCENT, "0.0%", Type.DATE, "dd/mm/yyyy",
                Type.DATETIME, "dd/mm/yyyy hh:mm");
        for (Type t : Type.values()) {
            CellStyle s = wb.createCellStyle();
            if (font != null) {
                s.setFont(font);
                s.setBorderTop(BorderStyle.THIN);
            }
            if (formats.containsKey(t)) s.setDataFormat(wb.createDataFormat().getFormat(formats.get(t)));
            m.put(t, s);
        }
        return m;
    }

    private static void write(Row row, List<Column> columns, List<Object> values, Map<Type, CellStyle> styles) {
        for (int c = 0; c < columns.size(); c++) {
            Object v = values.get(c);
            if (v == null) continue;
            Cell cell = row.createCell(c);
            cell.setCellStyle(styles.get(columns.get(c).type()));
            switch (v) {
                case Number n -> cell.setCellValue(n.doubleValue());
                case LocalDate d -> cell.setCellValue(d);
                case OffsetDateTime t -> cell.setCellValue(t.atZoneSameInstant(Sql.ZONE).toLocalDateTime());
                case LocalDateTime t -> cell.setCellValue(t);
                case Boolean b -> cell.setCellValue(b ? "Yes" : "No");
                default -> cell.setCellValue(String.valueOf(v));
            }
        }
    }

    private static void cell(Row row, int c, String value, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static String sheetName(String s) {
        String clean = s.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    // ------------------------------------------------------------------ PDF

    static byte[] pdf(Report r, String primaryColor) {
        Map<String, String> v = new HashMap<>();
        v.put("title", r.title());
        v.put("code", r.code());
        v.put("institution", r.institution());
        v.put("generated", "Generated " + DATE_TIME.format(r.generatedAt().atZoneSameInstant(Sql.ZONE)) + " by " + r.generatedBy());
        v.put("primaryColor", primaryColor);
        v.put("pageSize", r.columns().size() > 6 ? "A4 landscape" : "A4");
        Map<String, String> m = new HashMap<>();
        StringBuilder filters = new StringBuilder();
        for (String f : r.filterLines()) filters.append("<div>").append(HtmlTemplate.escape(f)).append("</div>");
        m.put("filters", filters.toString());
        StringBuilder t = new StringBuilder("<thead><tr>");
        for (Column c : r.columns()) {
            t.append("<th class=\"").append(align(c)).append("\">").append(HtmlTemplate.escape(c.label())).append("</th>");
        }
        t.append("</tr></thead><tbody>");
        List<List<Object>> rows = r.rows().size() > PDF_MAX_ROWS ? r.rows().subList(0, PDF_MAX_ROWS) : r.rows();
        for (List<Object> row : rows) t.append(tr(r.columns(), row, ""));
        if (r.rows().isEmpty()) t.append("<tr><td colspan=\"").append(r.columns().size()).append("\" class=\"muted\">Nothing to report for these filters.</td></tr>");
        if (r.totals() != null) t.append(tr(r.columns(), r.totals(), "total"));
        t.append("</tbody>");
        m.put("table", t.toString());
        StringBuilder notes = new StringBuilder();
        for (String n : r.notes()) notes.append("<p class=\"muted\">").append(HtmlTemplate.escape(n)).append("</p>");
        if (r.rows().size() > PDF_MAX_ROWS) {
            notes.append("<p class=\"muted\">The PDF shows the first ").append(PDF_MAX_ROWS).append(" of ").append(r.rows().size())
                    .append(" rows. Download the Excel file for the complete list.</p>");
        }
        m.put("notes", notes.toString());
        return PdfRenderer.render(TEMPLATE.render(v, m), r.title());
    }

    private static String tr(List<Column> columns, List<Object> row, String cls) {
        StringBuilder sb = new StringBuilder("<tr").append(cls.isEmpty() ? "" : " class=\"" + cls + "\"").append(">");
        for (int c = 0; c < columns.size(); c++) {
            sb.append("<td class=\"").append(align(columns.get(c))).append("\">").append(HtmlTemplate.escape(format(columns.get(c).type(), row.get(c))))
                    .append("</td>");
        }
        return sb.append("</tr>").toString();
    }

    private static String align(Column c) {
        return switch (c.type()) {
            case NUMBER, MONEY, PERCENT -> "r";
            default -> "l";
        };
    }

    static String format(Type type, Object v) {
        if (v == null) return "";
        return switch (type) {
            case NUMBER -> v instanceof Number n ? String.format(Locale.ENGLISH, "%,d", n.longValue()) : String.valueOf(v);
            case MONEY -> v instanceof Number n ? String.format(Locale.ENGLISH, "%,.2f", new BigDecimal(n.toString())) : String.valueOf(v);
            case PERCENT -> v instanceof Number n ? new BigDecimal(n.toString()).movePointRight(2).setScale(1, RoundingMode.HALF_UP) + "%" : String.valueOf(v);
            case DATE -> v instanceof LocalDate d ? DATE.format(d) : String.valueOf(v);
            case DATETIME -> v instanceof OffsetDateTime t ? DATE_TIME.format(t.atZoneSameInstant(Sql.ZONE)) : String.valueOf(v);
            default -> v instanceof Boolean b ? (b ? "Yes" : "No") : String.valueOf(v);
        };
    }
}
