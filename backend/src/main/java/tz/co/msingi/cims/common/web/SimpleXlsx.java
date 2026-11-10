package tz.co.msingi.cims.common.web;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A small Excel workbook of plain sheets: a bold header row, then rows of text, numbers and dates. */
public final class SimpleXlsx {

    private record SheetData(String name, List<String> header, List<List<Object>> rows, int[] widths) {
    }

    private final List<SheetData> sheets = new ArrayList<>();

    public SimpleXlsx sheet(String name, List<String> header, List<List<Object>> rows, int... widths) {
        sheets.add(new SheetData(name, header, rows, widths));
        return this;
    }

    public byte[] bytes() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("d mmm yyyy"));
            for (SheetData d : sheets) {
                Sheet s = wb.createSheet(d.name());
                int r = 0;
                if (d.header() != null && !d.header().isEmpty()) {
                    Row h = s.createRow(r++);
                    for (int c = 0; c < d.header().size(); c++) {
                        h.createCell(c).setCellValue(d.header().get(c));
                        h.getCell(c).setCellStyle(bold);
                    }
                    s.createFreezePane(0, 1);
                }
                for (List<Object> row : d.rows()) {
                    Row x = s.createRow(r++);
                    for (int c = 0; c < row.size(); c++) {
                        Object v = row.get(c);
                        if (v == null) continue;
                        if (v instanceof BigDecimal b) x.createCell(c).setCellValue(b.doubleValue());
                        else if (v instanceof Number n) x.createCell(c).setCellValue(n.doubleValue());
                        else if (v instanceof LocalDate ld) {
                            x.createCell(c).setCellValue(ld);
                            x.getCell(c).setCellStyle(date);
                        } else if (v instanceof Boolean b) x.createCell(c).setCellValue(b ? "Yes" : "No");
                        else x.createCell(c).setCellValue(v.toString());
                    }
                }
                int cols = d.header() == null ? 0 : d.header().size();
                for (int c = 0; c < cols; c++) s.setColumnWidth(c, (c < d.widths().length ? d.widths()[c] : 18) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A response that downloads the workbook. */
    public org.springframework.http.ResponseEntity<byte[]> download(String fileName) {
        return UploadedFile.download(fileName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes());
    }
}
