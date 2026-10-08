package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.finance.reports.FinanceReportService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.ACADEMIC_YEAR;
import static tz.co.msingi.cims.reporting.ReportModel.PERIOD;

/** Finance reports (SRS section 7). Collections and debtors come from the same service as the Fees screens, so the figures agree. */
@Component
class FinanceReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT, Role.MANAGEMENT_VIEWER, Role.AUDITOR);

    private final FinanceReportService finance;

    FinanceReports(NamedParameterJdbcTemplate jdbc, FinanceReportService finance) {
        super(jdbc);
        this.finance = finance;
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("FIN-01", "Finance", "Collections by fee item", "Money received in the period, by what it paid for.", ROLES, false,
                        List.of(PERIOD), f -> amounts(finance.collections(from(f), to(f)).byFeeItem(), "Fee item")),
                new Definition("FIN-02", "Finance", "Collections by day", "Money received each day of the period.", ROLES, false,
                        List.of(PERIOD), f -> byDay(amounts(finance.collections(from(f), to(f)).byDay(), "Day"))),
                new Definition("FIN-03", "Finance", "Collections by payment channel", "Money received through each bank or mobile channel.", ROLES,
                        false, List.of(PERIOD), f -> amounts(finance.collections(from(f), to(f)).byChannel(), "Channel")),
                new Definition("FIN-04", "Finance", "Receipts register", "Every payment received in the period, with receipt and payer.", ROLES, true,
                        List.of(PERIOD), this::receipts),
                new Definition("FIN-05", "Finance", "Debtors with ageing", "Annual-fee invoices with a balance, oldest first.", ROLES, true,
                        List.of(ACADEMIC_YEAR), this::debtors),
                new Definition("FIN-06", "Finance", "Debtor ageing summary", "Balances owed by how long they are overdue.", ROLES, false,
                        List.of(ACADEMIC_YEAR), f -> amounts(finance.debtors(f.academicYearId()).byBucket(), "Ageing")),
                new Definition("FIN-07", "Finance", "Reconciliation exceptions", "GePG reconciliation exceptions in the period and how each was resolved.",
                        ROLES, false, List.of(PERIOD), this::exceptions));
    }

    private static LocalDate from(Filter f) {
        return f.from() != null ? f.from() : LocalDate.now(Sql.ZONE).withDayOfMonth(1);
    }

    private static LocalDate to(Filter f) {
        return f.to() != null ? f.to() : LocalDate.now(Sql.ZONE);
    }

    private static Data amounts(List<FinanceReportService.Amount> list, String label) {
        List<List<Object>> r = new ArrayList<>();
        java.math.BigDecimal total = list.stream().map(FinanceReportService.Amount::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        for (FinanceReportService.Amount a : list) {
            r.add(new ArrayList<>(List.of(a.label(), a.count(), a.amount(), total.signum() == 0 ? 0.0 : a.amount().doubleValue() / total.doubleValue())));
        }
        Data d = new Data(List.of(Column.text("key", label), Column.number("count", "Count"), Column.money("amount", "Amount (TZS)"),
                Column.percent("share", "Share")), r).withTotals();
        d.totals().set(3, total.signum() == 0 ? null : 1.0);
        return d;
    }

    /** Day labels are ISO dates: make them real dates so Excel can sort and chart them. */
    private static Data byDay(Data d) {
        d.rows().forEach(r -> r.set(0, LocalDate.parse(String.valueOf(r.get(0)))));
        List<Column> cols = new ArrayList<>(d.columns());
        cols.set(0, Column.date("day", "Day"));
        return new Data(cols, d.rows(), d.totals(), d.notes());
    }

    private Data receipts(Filter f) {
        List<List<Object>> r = new ArrayList<>();
        for (FinanceReportService.PaymentRow p : finance.collections(from(f), to(f)).rows()) {
            r.add(new ArrayList<>(java.util.Arrays.asList(p.receiptNo(), p.paidAt(), p.payerName(), p.invoiceNo(), p.purpose(), p.channel(),
                    p.pspName(), p.amount(), p.credit())));
        }
        return new Data(List.of(Column.text("receipt", "Receipt"), Column.text("paidAt", "Paid"), Column.text("payer", "Payer"),
                Column.text("invoice", "Invoice"), Column.text("purpose", "Purpose"), Column.text("channel", "Channel"), Column.text("psp", "Bank / provider"),
                Column.money("amount", "Amount (TZS)"), Column.money("credit", "Held as credit")), r).withTotals();
    }

    private Data debtors(Filter f) {
        List<List<Object>> r = new ArrayList<>();
        for (FinanceReportService.DebtorRow d : finance.debtors(f.academicYearId()).rows()) {
            r.add(new ArrayList<>(java.util.Arrays.asList(d.payerName(), d.registrationNo() == null ? "Applicant" : d.registrationNo(), d.phone(),
                    d.invoiceNo(), d.total(), d.paid(), d.balance(), d.dueDate(), d.daysOverdue(), bucket(d.bucket()))));
        }
        return new Data(List.of(Column.text("payer", "Payer"), Column.text("regNo", "Registration no."), Column.text("phone", "Phone"),
                Column.text("invoice", "Invoice"), Column.money("billed", "Billed"), Column.money("paid", "Paid"), Column.money("balance", "Balance"),
                Column.date("due", "Due"), Column.number("days", "Days overdue"), Column.text("ageing", "Ageing")), r).withTotals();
    }

    private Data exceptions(Filter f) {
        Sql q = new Sql().raw("r.recon_date between :from and :to").param("from", from(f)).param("to", to(f));
        List<List<Object>> r = rows("""
                select r.recon_date, i.kind, i.status, i.gepg_receipt_no, coalesce(i.bill_id, i.control_no), i.gepg_amount, i.cims_amount,
                       i.resolution, i.note, i.resolved_by_name, i.resolved_at
                from gepg_reconciliation_items i join gepg_reconciliations r on r.id = i.reconciliation_id
                where i.kind <> 'MATCHED' and """ + q.where.substring(5) + " order by r.recon_date, i.id", q);
        r.forEach(x -> {
            x.set(1, AdmissionReports.label(String.valueOf(x.get(1))));
            x.set(2, AdmissionReports.label(String.valueOf(x.get(2))));
            if (x.get(7) != null) x.set(7, AdmissionReports.label(String.valueOf(x.get(7))));
        });
        return new Data(List.of(Column.date("day", "Day"), Column.text("kind", "Exception"), Column.text("status", "Status"),
                Column.text("receipt", "GePG receipt"), Column.text("bill", "Bill / control no."), Column.money("gepg", "GePG amount"),
                Column.money("cims", "CIMS amount"), Column.text("resolution", "Resolution"), Column.text("note", "Explanation"),
                Column.text("by", "Resolved by"), Column.dateTime("at", "Resolved")), r);
    }

    private static String bucket(String b) {
        return switch (b) {
            case "NOT_DUE" -> "Not yet due";
            case "1-30" -> "1–30 days";
            case "31-60" -> "31–60 days";
            case "61-90" -> "61–90 days";
            case "OVER_90" -> "Over 90 days";
            default -> b;
        };
    }
}
