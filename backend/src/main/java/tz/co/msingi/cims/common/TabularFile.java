package tz.co.msingi.cims.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a CSV or Excel (.xlsx) file into rows keyed by column name. Column names are compared without case, spaces or
 * punctuation ("Registration No" = "registration_no" = "registrationno"); dates in Excel cells come out as yyyy-MM-dd.
 */
public final class TabularFile {

    public static final int MAX_ROWS = 20_000;

    private TabularFile() {
    }

    public static String key(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static List<Map<String, String>> read(byte[] data) {
        if (data == null || data.length == 0) throw new BusinessRuleException("EMPTY_FILE", "The file is empty.");
        boolean zip = data.length > 3 && data[0] == 'P' && data[1] == 'K';
        List<List<String>> table = zip ? excel(data) : Csv.parse(new String(data, StandardCharsets.UTF_8).replace("﻿", ""));
        if (table.isEmpty()) throw new BusinessRuleException("EMPTY_FILE", "The file has no rows.");
        List<String> headers = table.get(0).stream().map(TabularFile::key).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("_row", String.valueOf(r + 1));
            for (int c = 0; c < headers.size(); c++) {
                String v = c < cells.size() ? cells.get(c) : null;
                if (!headers.get(c).isEmpty()) row.put(headers.get(c), v == null || v.isBlank() ? null : v.trim());
            }
            rows.add(row);
            if (rows.size() > MAX_ROWS) throw new BusinessRuleException("TOO_MANY_ROWS", "Split the file: at most " + MAX_ROWS + " rows at a time.");
        }
        return rows;
    }

    private static List<List<String>> excel(byte[] data) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<List<String>> out = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) cells.add(null);
                    else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    else cells.add(fmt.formatCellValue(cell));
                }
                out.add(cells);
            }
            return out;
        } catch (BusinessRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessRuleException("FILE_UNREADABLE", "The file could not be read. Use the template: CSV or Excel (.xlsx).");
        }
    }
}
