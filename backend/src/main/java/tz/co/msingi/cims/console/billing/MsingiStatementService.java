package tz.co.msingi.cims.console.billing;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.TabularFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.finance.collection.PaymentProvider;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank statements of Msingi's own accounts (console › Billing › Payments › Import bank statement): every credit that quotes a
 * Msingi invoice's payment reference (or its BillPay control number) is recorded against the invoice, at once and only once,
 * with what that delivers (an SMS bundle, access restored). The other lines are listed for Msingi to look at.
 */
@Service
@RequiredArgsConstructor
public class MsingiStatementService {

    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    private static final ObjectMapper JSON = new ObjectMapper();   // Jackson 3 reads java.time types without a module
    private static final Pattern REF = Pattern.compile("(?<!\\d)(\\d{4})?(7\\d{11})(?!\\d)");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"), DateTimeFormatter.ofPattern("d.M.yyyy"), DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH));
    private static final List<String> DATE_COLS = List.of("valuedate", "transactiondate", "date", "postingdate", "trandate", "txndate");
    private static final List<String> CREDIT_COLS = List.of("credit", "creditamount", "credits", "deposit", "deposits", "moneyin", "amountin", "cr", "amount");
    private static final List<String> TX_COLS = List.of("transactionid", "transactionreference", "bankreference", "referenceno", "refno",
            "referencenumber", "transid", "tranid", "receipt", "receiptno", "chequeno");
    private static final List<String> TEXT_COLS = List.of("narration", "description", "details", "particulars", "transactiondetails", "remarks",
            "reference", "payerreference", "customerreference", "memo");

    public record Line(int row, String date, BigDecimal amount, String text, String transactionId, String outcome, String reference,
                       String receiptNo, String invoices) {
    }

    public record ImportView(Long id, String fileName, String provider, int total, int recorded, int already, int unmatched, List<Line> lines,
                             String importedByName, OffsetDateTime importedAt) {
    }

    private final JdbcTemplate jdbc;
    private final MsingiBillingService billing;
    private final MsingiCollectionService collection;
    private final PlatformAuditService audit;

    @Transactional
    public ImportView importStatement(String fileName, byte[] data, String providerCode) {
        PaymentProvider bank = MsingiBillingService.provider(providerCode);
        if (bank == null) throw new BusinessRuleException("BANK_REQUIRED", "Choose the bank the statement comes from.");
        List<Map<String, String>> rows = TabularFile.read(data);
        if (rows.isEmpty()) throw new BusinessRuleException("EMPTY_FILE", "The statement has no lines.");
        Map<String, String> first = rows.get(0);
        String dateCol = pick(first, DATE_COLS), creditCol = pick(first, CREDIT_COLS), txCol = pick(first, TX_COLS);
        if (dateCol == null || creditCol == null) {
            throw new BusinessRuleException("STATEMENT_COLUMNS", "The statement needs a date column and a credit (or amount) column with headers in its first row.");
        }
        String namba = collection.savedBillPayNamba();
        List<Line> out = new ArrayList<>();
        int recorded = 0, already = 0, unmatched = 0;
        for (Map<String, String> r : rows) {
            int row = Integer.parseInt(r.get("_row"));
            BigDecimal amount = amount(r.get(creditCol));
            if (amount == null || amount.signum() <= 0) continue;   // debits and blank lines
            LocalDate date = date(r.get(dateCol));
            StringBuilder text = new StringBuilder();
            for (String k : TEXT_COLS) if (r.get(k) != null && !k.equals(txCol)) text.append(text.isEmpty() ? "" : " · ").append(r.get(k));
            String tx = txCol == null ? null : r.get(txCol);
            String ref = reference(text + " " + (tx == null ? "" : tx), namba);
            if (tx == null) tx = "STMT-" + (date == null ? "nodate" : date) + "-" + amount.toPlainString() + "-" + Integer.toHexString(text.toString().hashCode());
            String d = date == null ? null : date.toString();
            if (ref == null) {
                out.add(new Line(row, d, amount, text.toString(), tx, "UNMATCHED", null, null, null));
                unmatched++;
                continue;
            }
            String key = bank.name() + ":" + tx.trim();
            boolean seen = !jdbc.queryForList("select id from billing_payments where transaction_key = ?", Long.class, key).isEmpty();
            OffsetDateTime at = (date == null ? LocalDate.now(TZ) : date).atTime(12, 0).atZone(TZ).toOffsetDateTime();
            BillingModels.PaymentRow p = billing.online(ref, bank.name(), tx, amount, at, "BANK");
            if (p == null) {
                out.add(new Line(row, d, amount, text.toString(), tx, "UNKNOWN_REFERENCE", ref, null, null));
                unmatched++;
            } else if (seen) {
                out.add(new Line(row, d, amount, text.toString(), tx, "ALREADY_RECORDED", ref, p.receiptNo(), p.invoices()));
                already++;
            } else {
                out.add(new Line(row, d, amount, text.toString(), tx, "RECORDED", ref, p.receiptNo(), p.invoices()));
                recorded++;
            }
        }
        if (out.isEmpty()) throw new BusinessRuleException("NO_CREDITS", "The statement has no money coming in.");
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into billing_statement_imports (file_name, provider, total_lines, recorded, already, unmatched, result, imported_by_name)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, clean(fileName), bank.name(), out.size(), recorded, already, unmatched,
                    JSON.writeValueAsString(out), CurrentUser.platformName());
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
        audit.record("BILLING_STATEMENT_IMPORTED", null, "BillingStatementImport", id,
                Map.of("bank", bank.name(), "recorded", recorded, "already", already, "unmatched", unmatched));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<ImportView> recent() {
        return jdbc.queryForList("select id from billing_statement_imports order by id desc limit 20", Long.class).stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public ImportView get(Long id) {
        return jdbc.queryForObject("select * from billing_statement_imports where id = ?", (rs, i) -> {
            List<Line> lines;
            try {
                lines = JSON.readValue(rs.getString("result"), new TypeReference<List<Line>>() { });
            } catch (Exception e) {
                lines = List.of();
            }
            return new ImportView(rs.getLong("id"), rs.getString("file_name"), rs.getString("provider"), rs.getInt("total_lines"), rs.getInt("recorded"),
                    rs.getInt("already"), rs.getInt("unmatched"), lines, rs.getString("imported_by_name"), rs.getObject("imported_at", OffsetDateTime.class));
        }, id);
    }

    /** The first valid Msingi payment reference quoted (alone or inside a BillPay control number), digits grouped or not. */
    static String reference(String text, String namba) {
        String joined = text == null ? "" : text.replaceAll("(?<=\\d)[ \\-.](?=\\d)", "");
        Matcher m = REF.matcher(joined);
        while (m.find()) {
            String ref = m.group(2);   // a BillPay control number carries the reference in its last 12 digits
            if (MsingiBillingService.validReference(ref)) return ref;
        }
        return null;
    }

    private static String pick(Map<String, String> row, List<String> names) {
        for (String n : names) if (row.containsKey(n)) return n;
        return null;
    }

    private static BigDecimal amount(String v) {
        if (v == null) return null;
        String d = v.replace(",", "").replace("TZS", "").replace("Tsh", "").trim();
        if (d.isEmpty() || d.equals("-")) return null;
        try {
            return new BigDecimal(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() > 10 && s.charAt(10) == 'T') s = s.substring(0, 10);
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // next form
            }
        }
        return null;
    }

    private static String clean(String name) {
        String n = name == null ? "statement" : name.replaceAll(".*[\\\\/]", "");
        return n.length() > 200 ? n.substring(0, 200) : n;
    }
}
