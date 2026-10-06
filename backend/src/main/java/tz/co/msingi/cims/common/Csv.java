package tz.co.msingi.cims.common;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** Minimal RFC 4180 CSV reading and writing for file exchanges (NACTVET templates, committee packs). */
public final class Csv {

    private Csv() {
    }

    /** One CSV line from cell values; neutralises spreadsheet formula injection. */
    public static String line(Object... cells) {
        StringJoiner j = new StringJoiner(",", "", "\n");
        for (Object c : cells) {
            j.add(cell(c == null ? null : c.toString()));
        }
        return j.toString();
    }

    public static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        s = s.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s + "\"" : s;
    }

    /** Parses CSV text into rows of cells. Handles quoted cells, escaped quotes, CRLF and a UTF-8 BOM. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = text.startsWith("﻿") ? 1 : 0;
        for (; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                addIfNotBlank(rows, row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted value in CSV");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            addIfNotBlank(rows, row);
        }
        return rows;
    }

    private static void addIfNotBlank(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(c -> !c.isBlank())) rows.add(row);
    }
}
