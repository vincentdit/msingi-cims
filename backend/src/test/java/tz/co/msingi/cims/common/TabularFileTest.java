package tz.co.msingi.cims.common;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Excel files as institutions keep them: formulas give their values, real dates become dates, numbers keep no decimals. */
class TabularFileTest {

    @Test
    void readsFormulaValuesDatesAndNumbers() throws Exception {
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Students");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Registration No");
            h.createCell(1).setCellValue("first_name");
            h.createCell(2).setCellValue("date_of_birth");
            h.createCell(3).setCellValue("phone");
            h.createCell(4).setCellValue("amount");
            Row r = s.createRow(1);
            r.createCell(0).setCellFormula("\"BIHAS/NTA4/24/\"&TEXT(7,\"0000\")");
            r.createCell(1).setCellValue("Asha");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            r.createCell(2).setCellValue(LocalDate.of(2005, 3, 14));
            r.getCell(2).setCellStyle(date);
            r.createCell(3).setCellValue(712345678);
            r.createCell(4).setCellFormula("300000+50000");
            wb.write(out);
            file = out.toByteArray();
        }
        List<Map<String, String>> rows = TabularFile.read(file);
        assertThat(rows).hasSize(1);
        Map<String, String> row = rows.get(0);
        assertThat(row.get(TabularFile.key("registration_no"))).isEqualTo("BIHAS/NTA4/24/0007");
        assertThat(row.get(TabularFile.key("date_of_birth"))).isEqualTo("2005-03-14");
        assertThat(row.get(TabularFile.key("phone"))).isEqualTo("712345678");
        assertThat(row.get(TabularFile.key("amount"))).isEqualTo("350000");
    }
}
