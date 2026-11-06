package tz.co.msingi.cims.finance.reconciliation;

import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.collection.PaymentReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the file the Accountant imports for a day. Two shapes are accepted:
 * <ul>
 *   <li><b>Reconciliation files</b> (GePG's, or a collector's export with a Reference column): every row is a collection; any invalid row
 *       rejects the file.</li>
 *   <li><b>Bank and mobile money statements</b> as the institution downloads them (a Narration/Description/Details column, or separate
 *       Credit and Debit columns): only credits dated on the day are taken, debits, failed transactions, opening balances and other days
 *       are skipped, and the 12-digit CIMS payment reference is found in the reference or narration text, even with spaces or dashes.
 *       Lines before the column headings (account name, period) are ignored.</li>
 * </ul>
 */
final class ReconciliationFile {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String HEADER = "PayRefId,SpBillId,BillCtrNum,PspTrxId,PspName,PaidAmt,Ccy,TrxDtTm,UsdPayChnl,PyrCellNum";

    record Line(int lineNo, String receipt, String bill, String control, String pspReceipt, String psp, BigDecimal amount,
                OffsetDateTime paidAt, String channel, String phone) {
    }

    /** The lines to match, and how many statement rows were left out (debits, failed, other days). */
    record Parsed(List<Line> lines, boolean statement, int skipped) {
    }

    /** Accepted column names (GePG's, collectors', banks' and mobile money operators'), lower case without separators. */
    private static final Map<String, String> COLUMNS = new HashMap<>();

    private static void alias(String key, String... names) {
        for (String n : names) COLUMNS.put(n, key);
    }

    static {
        alias("receipt", "payrefid", "gepgreceiptno", "receipt", "receiptno", "receiptnumber", "transactionid", "transid", "trxid", "txnid",
                "transactionno", "transactionnumber", "transactionreference", "transref", "tranid", "trannumber", "trnid", "trnref", "mpesareceipt",
                "mpesareceiptno", "documentno", "chequeno", "serialno");
        alias("bill", "spbillid", "billid");
        alias("control", "billctrnum", "controlno", "controlnumber", "reference", "paymentreference", "accountreference", "billreference", "ref",
                "refno", "referenceno", "referencenumber", "customerreference", "payerreference", "billnumber");
        alias("narration", "narration", "naration", "narrative", "description", "details", "particulars", "remarks", "transactiondetails",
                "memo", "otherparty", "maelezo");
        alias("pspReceipt", "psptrxid", "pspreceiptno");
        alias("psp", "pspname", "psp", "provider", "operator", "bank", "channelname");
        alias("amount", "paidamt", "amount", "transactionamount", "amounttzs");
        alias("credit", "credit", "credits", "creditamount", "cr", "paidin", "moneyin", "amountin", "deposit", "deposits", "receipts", "lodgement");
        alias("debit", "debit", "debits", "debitamount", "dr", "withdrawn", "withdrawal", "withdrawals", "paidout", "moneyout", "amountout");
        alias("currency", "ccy", "currency");
        alias("paidAt", "trxdttm", "paidat", "date", "transactiondate", "paymentdate", "valuedate", "postingdate", "postdate", "trandate",
                "bookingdate", "completiontime", "completeddate", "datetime", "time", "tarehe");
        alias("channel", "usdpaychnl", "channel");
        alias("phone", "pyrcellnum", "phone", "msisdn", "phonenumber", "mobilenumber", "sender");
        alias("status", "status", "transactionstatus");
    }

    /** A CIMS payment reference written into free text: "8" and eleven more digits, possibly grouped with spaces or dashes. */
    private static final Pattern REFERENCE = Pattern.compile("(?<!\\d)8(?:[ -]?\\d){11}(?!\\d)");
    private static final Pattern DONE = Pattern.compile("(?i)\\s*(completed?|success(ful)?|ok|posted|done|approved|cleared|paid)?\\s*");

    private static final List<DateTimeFormatter> DATES = new ArrayList<>();

    static {
        for (String d : List.of("d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "d-MMM-uuuu", "d MMM uuuu", "d-MMM-uu", "d MMM, uuuu", "MMM d, uuuu", "uuuu/M/d")) {
            for (String t : List.of(" H:mm:ss", " H:mm", " h:mm:ss a", " h:mm a", "")) {
                DATES.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(d + t).toFormatter(Locale.ENGLISH));
            }
        }
    }

    private ReconciliationFile() {
    }

    static Parsed parse(String text, LocalDate date) {
        List<List<String>> rows;
        try {
            rows = Csv.parse(text, separatorOf(text));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_FILE", e.getMessage());
        }
        if (rows.isEmpty()) throw new BusinessRuleException("INVALID_FILE", "The file is empty");

        int headerRow = -1;
        Map<String, Integer> col = Map.of();
        for (int r = 0; r < Math.min(rows.size(), 30) && headerRow < 0; r++) {
            Map<String, Integer> c = columns(rows.get(r));
            boolean money = c.containsKey("amount") || c.containsKey("credit");
            boolean identifies = c.containsKey("receipt") || c.containsKey("control") || c.containsKey("bill") || c.containsKey("narration");
            if (money && identifies) {
                headerRow = r;
                col = c;
            }
        }
        boolean statement = headerRow >= 0 && (col.containsKey("narration") || col.containsKey("credit")
                || !(col.containsKey("bill") || col.containsKey("control")));
        if (headerRow < 0 || !statement && !col.containsKey("receipt")) {
            throw new BusinessRuleException("INVALID_FILE", "The file needs at least PayRefId, PaidAmt and SpBillId or BillCtrNum columns (GePG: " + HEADER
                    + "), TransactionId, Amount and Reference for a collector's file, or a bank or mobile money statement with a Narration, "
                    + "Description or Details column and an Amount or Credit column");
        }
        return statement ? statement(rows, headerRow, col, date) : strict(rows, headerRow, col, date);
    }

    /** GePG's file or a collector's: every row counts, and a wrong row rejects the file. */
    private static Parsed strict(List<List<String>> rows, int headerRow, Map<String, Integer> col, LocalDate date) {
        List<String> errors = new ArrayList<>();
        List<Line> out = new ArrayList<>();
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int lineNo = r + 1;
            String receipt = cell(row, col, "receipt");
            String amountText = cell(row, col, "amount");
            String currency = cell(row, col, "currency");
            if (receipt.isEmpty()) { errors.add("Line " + lineNo + ": PayRefId is missing"); continue; }
            if (receipt.length() > 40) { errors.add("Line " + lineNo + ": PayRefId is too long"); continue; }
            BigDecimal amount = money(amountText);
            if (amount == null) { errors.add("Line " + lineNo + ": PaidAmt '" + amountText + "' is not a number"); continue; }
            if (amount.signum() <= 0) { errors.add("Line " + lineNo + ": PaidAmt must be more than zero"); continue; }
            if (!currency.isEmpty() && !currency.equalsIgnoreCase("TZS")) { errors.add("Line " + lineNo + ": only TZS collections are reconciled here"); continue; }
            OffsetDateTime paidAt;
            try {
                paidAt = parseTime(cell(row, col, "paidAt"), date);
            } catch (DateTimeParseException e) {
                errors.add("Line " + lineNo + ": TrxDtTm '" + cell(row, col, "paidAt") + "' is not a date and time"); continue;
            }
            String control = blankToNull(cell(row, col, "control"));
            String found = control == null ? null : findReference(control);
            out.add(new Line(lineNo, receipt, blankToNull(cell(row, col, "bill")), found != null ? found : control,
                    truncate(blankToNull(cell(row, col, "pspReceipt")), 60), truncate(blankToNull(cell(row, col, "psp")), 60), amount, paidAt,
                    truncate(blankToNull(cell(row, col, "channel")), 30), truncate(blankToNull(cell(row, col, "phone")), 20)));
        }
        rejectIfAny(errors);
        return new Parsed(out, false, 0);
    }

    /** A bank or mobile money statement: only the day's completed credits are taken. */
    private static Parsed statement(List<List<String>> rows, int headerRow, Map<String, Integer> col, LocalDate date) {
        List<String> errors = new ArrayList<>();
        List<Line> out = new ArrayList<>();
        int skipped = 0;
        int otherDays = 0;
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int lineNo = r + 1;
            String status = cell(row, col, "status");
            if (!DONE.matcher(status).matches()) { skipped++; continue; }
            String currency = cell(row, col, "currency");
            if (!currency.isEmpty() && !currency.equalsIgnoreCase("TZS")) { skipped++; continue; }

            String amountText = col.containsKey("credit") ? cell(row, col, "credit") : cell(row, col, "amount");
            if (amountText.isBlank()) { skipped++; continue; } // a debit, or a balance line
            BigDecimal amount = money(amountText);
            if (amount == null) { errors.add("Line " + lineNo + ": the amount '" + amountText + "' is not a number"); continue; }
            if (amount.signum() <= 0) { skipped++; continue; }

            String when = cell(row, col, "paidAt");
            if (when.isEmpty() && col.containsKey("paidAt")) { skipped++; continue; } // opening or closing balance
            OffsetDateTime paidAt;
            try {
                paidAt = parseTime(when, date);
            } catch (DateTimeParseException e) {
                errors.add("Line " + lineNo + ": '" + when + "' is not a date"); continue;
            }
            if (!paidAt.atZoneSameInstant(ZONE).toLocalDate().equals(date)) { skipped++; otherDays++; continue; }

            String narration = cell(row, col, "narration");
            String refCell = cell(row, col, "control");
            String reference = findReference(refCell);
            if (reference == null) reference = findReference(narration);
            if (reference == null) reference = truncate(blankToNull(refCell), 40);

            String receipt = cell(row, col, "receipt");
            if (receipt.isEmpty()) receipt = "ST-" + fingerprint(date, row);
            if (receipt.length() > 40) { errors.add("Line " + lineNo + ": the transaction id is too long"); continue; }

            out.add(new Line(lineNo, receipt, blankToNull(cell(row, col, "bill")), reference, null,
                    truncate(blankToNull(cell(row, col, "psp")), 60), amount, paidAt,
                    truncate(blankToNull(cell(row, col, "channel")), 30), truncate(blankToNull(phoneOf(cell(row, col, "phone"))), 20)));
        }
        rejectIfAny(errors);
        if (out.isEmpty() && otherDays > 0) {
            throw new BusinessRuleException("NO_LINES_FOR_DAY", "None of the " + otherDays + " credit(s) in the statement is dated " + date
                    + ". Choose the day the statement covers, or import a statement that includes it.");
        }
        return new Parsed(out, true, skipped);
    }

    /** The valid CIMS payment reference in a cell of free text, without spaces or dashes, or null. */
    static String findReference(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = REFERENCE.matcher(text);
        while (m.find()) {
            String ref = m.group().replaceAll("[ -]", "");
            if (PaymentReference.isValid(ref)) return ref;
        }
        return null;
    }

    static OffsetDateTime parseTime(String s, LocalDate date) {
        if (s == null || s.isBlank()) return date.atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        String raw = s.trim().replaceAll("\\s+", " ");
        String t = raw.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(t).atZone(ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDate.parse(t).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
                } catch (DateTimeParseException e3) {
                    for (DateTimeFormatter f : DATES) {
                        try {
                            return LocalDateTime.parse(raw, f).atZone(ZONE).toOffsetDateTime();
                        } catch (DateTimeParseException e4) {
                            try {
                                return LocalDate.parse(raw, f).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
                            } catch (DateTimeParseException e5) {
                                // next pattern
                            }
                        }
                    }
                    throw e3;
                }
            }
        }
    }

    /** "1,250,000.00", "TZS 50,000", "50,000.00 CR", "(1,000)" (negative); null if it is not an amount. */
    static BigDecimal money(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT).replaceAll("\\b(TZS|TSH|TSHS)\\b\\.?", "").replace(",", "").replace(" ", "");
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length() - 1); }
        if (t.endsWith("CR")) t = t.substring(0, t.length() - 2);
        else if (t.endsWith("DR")) { negative = true; t = t.substring(0, t.length() - 2); }
        try {
            BigDecimal v = new BigDecimal(t).setScale(2, RoundingMode.HALF_UP);
            return negative ? v.negate() : v;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> col = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String key = COLUMNS.get(header.get(c).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
            if (key != null) col.putIfAbsent(key, c);
        }
        return col;
    }

    /** Comma, or ';' or tab when the first lines use those instead. */
    private static char separatorOf(String text) {
        String head = text.lines().limit(30).reduce("", (a, b) -> a + "\n" + b);
        long commas = head.chars().filter(ch -> ch == ',').count();
        long semis = head.chars().filter(ch -> ch == ';').count();
        long tabs = head.chars().filter(ch -> ch == '\t').count();
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static String phoneOf(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9+]", "");
        return d.isEmpty() ? null : d;
    }

    /** A stable id for a statement row that has none, so importing the same statement again finds the same lines. */
    private static String fingerprint(LocalDate date, List<String> row) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((date + "|" + String.join("|", row).trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void rejectIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("INVALID_FILE", errors.size() + " line(s) need correcting: "
                    + String.join("; ", errors.subList(0, Math.min(10, errors.size()))) + (errors.size() > 10 ? "; …" : ""));
        }
    }

    private static String cell(List<String> row, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
