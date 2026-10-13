package tz.co.msingi.cims.finance.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.adjustments.AdjustmentService;
import tz.co.msingi.cims.finance.reports.FinanceReportService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Bursar's finance dashboard and the Accountant's desk (FR-FIN-12, BP-06, BP-17): collections of the year's annual fees against the
 * Bursar's milestones, billed / paid / owed per programme and year of study, debtor ageing and the largest debtors, cash received month
 * by month and by purpose, and the work waiting (reconciliation exceptions, waivers and refunds to approve or pay out, sponsor shares not
 * yet invoiced, sponsor invoices unpaid, control numbers that failed), with next steps. Also collection targets and the postings export
 * for the accounting system (FR-FIN-11).
 */
@Service
@RequiredArgsConstructor
public class FinanceDashboardService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final FinanceReportService reports;
    private final AdjustmentService adjustments;
    private final AuditService audit;

    public record YearOption(Long id, String code, LocalDate startDate, LocalDate endDate) {
    }

    public record Milestone(LocalDate dueDate, BigDecimal percent, BigDecimal expected, boolean passed) {
    }

    public record ProgrammeRow(Long programmeId, String code, String name, int yearOfStudy, long invoices, BigDecimal billed, BigDecimal paid,
                               BigDecimal sponsorPending, BigDecimal balance, BigDecimal percent) {
    }

    public record Amount(String key, String label, BigDecimal amount, long count) {
    }

    public record Debtor(String invoiceNo, String payerName, String registrationNo, String phone, BigDecimal balance, long daysOverdue) {
    }

    public record Work(long reconciliationExceptions, LocalDate lastSignedOffMonth, boolean lastMonthSignedOff, long adjustmentsOpen,
                       BigDecimal adjustmentsOpenAmount, long adjustmentsForMe, long refundsToPay, long sponsorInvoicesOpen,
                       BigDecimal sponsorOutstanding, BigDecimal sponsorNotInvoiced, long controlNumberFailures) {
    }

    public record Step(String severity, String text, String link) {
    }

    public record Dashboard(List<YearOption> years, Long academicYearId, String academicYear, BigDecimal billed, BigDecimal paid,
                            BigDecimal sponsorPending, BigDecimal waived, BigDecimal outstanding, BigDecimal percent, List<Milestone> targets,
                            Milestone currentTarget, Boolean onTrack, List<ProgrammeRow> programmes, List<Amount> ageing, List<Debtor> topDebtors,
                            List<Amount> monthly, List<Amount> byPurpose, BigDecimal cashThisMonth, BigDecimal cashToday, Work work, List<Step> next) {
    }

    public record TargetDto(LocalDate dueDate, BigDecimal percent) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Long academicYearId) {
        List<YearOption> years = jdbc.query("select id, code, start_date, end_date from academic_years order by start_date desc",
                (r, i) -> new YearOption(r.getLong(1), r.getString(2), r.getObject(3, LocalDate.class), r.getObject(4, LocalDate.class)));
        LocalDate today = LocalDate.now(ZONE);
        YearOption year = academicYearId != null ? years.stream().filter(y -> y.id().equals(academicYearId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_YEAR", "No such academic year"))
                : years.stream().filter(y -> y.startDate() != null && !y.startDate().isAfter(today) && (y.endDate() == null || !y.endDate().isBefore(today)))
                .findFirst().orElse(years.stream().filter(y -> y.startDate() != null && !y.startDate().isAfter(today)).findFirst()
                        .orElse(years.isEmpty() ? null : years.get(years.size() - 1)));
        Long yid = year == null ? null : year.id();

        // annual fees of the year, by programme and year of study (from the fee structure the invoice was billed under)
        List<ProgrammeRow> rows = yid == null ? List.of() : jdbc.query("""
                select p.id, p.code, p.name, coalesce(fs.year_of_study, 0) as y, count(i.id) as n,
                       sum(i.total_amount) as billed, sum(i.paid_amount) as paid,
                       coalesce(sum((select coalesce(sum(l.amount - l.paid_amount), 0) from invoice_lines l join invoices si on si.id = l.invoice_id
                                     where l.beneficiary_invoice_id = i.id and si.status not in ('CANCELLED', 'CREDITED'))), 0) as sponsor
                from invoices i left join fee_structures fs on fs.id = i.fee_structure_id left join programmes p on p.id = fs.programme_id
                where i.purpose = 'ANNUAL_FEES' and i.academic_year_id = ? and i.status not in ('CANCELLED', 'CREDITED')
                group by p.id, p.code, p.name, fs.year_of_study order by p.code, y""", (r, i) -> {
            BigDecimal billed = r.getBigDecimal("billed"), paid = r.getBigDecimal("paid");
            return new ProgrammeRow(r.getObject(1) == null ? null : r.getLong(1), r.getString(2) == null ? "—" : r.getString(2), r.getString(3),
                    r.getInt("y"), r.getLong("n"), billed, paid, r.getBigDecimal("sponsor"), billed.subtract(paid), pct(paid, billed));
        }, yid);
        BigDecimal billed = sum(rows.stream().map(ProgrammeRow::billed).toList()), paid = sum(rows.stream().map(ProgrammeRow::paid).toList());
        BigDecimal sponsor = sum(rows.stream().map(ProgrammeRow::sponsorPending).toList());
        BigDecimal waived = yid == null ? BigDecimal.ZERO : jdbc.queryForObject("""
                select coalesce(sum(p.amount), 0) from payments p join invoices i on i.id = p.invoice_id
                where p.source = 'INTERNAL' and p.channel = 'WAIVER' and i.purpose = 'ANNUAL_FEES' and i.academic_year_id = ?""", BigDecimal.class, yid);
        BigDecimal percent = pct(paid, billed);

        List<Milestone> targets = yid == null ? List.of() : jdbc.query("select due_date, percent from finance_targets where academic_year_id = ? order by due_date",
                (r, i) -> {
                    LocalDate due = r.getObject(1, LocalDate.class);
                    BigDecimal p = r.getBigDecimal(2);
                    return new Milestone(due, p, billed.multiply(p).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP), !due.isAfter(today));
                }, yid);
        Milestone current = targets.stream().filter(Milestone::passed).reduce((a, b) -> b).orElse(null);
        Boolean onTrack = current == null || billed.signum() == 0 ? null : percent.compareTo(current.percent()) >= 0;

        FinanceReportService.Debtors debtors = reports.debtors(yid);
        List<Amount> ageing = debtors.byBucket().stream().map(a -> new Amount(a.key(), a.label(), a.amount(), a.count())).toList();
        List<Debtor> top = debtors.rows().stream().limit(10).map(d -> new Debtor(d.invoiceNo(), d.payerName(), d.registrationNo(), d.phone(), d.balance(),
                d.daysOverdue())).toList();

        // money received (GePG and bank; internal postings are not cash), month by month and by purpose, over twelve months
        YearMonth now = YearMonth.now(ZONE);
        Map<YearMonth, BigDecimal> perMonth = new LinkedHashMap<>();
        Map<YearMonth, Long> perMonthCount = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            perMonth.put(now.minusMonths(i), BigDecimal.ZERO);
            perMonthCount.put(now.minusMonths(i), 0L);
        }
        LocalDate since = now.minusMonths(11).atDay(1);
        jdbc.query("""
                select extract(year from paid_at at time zone 'Africa/Dar_es_Salaam'), extract(month from paid_at at time zone 'Africa/Dar_es_Salaam'),
                       sum(amount), count(*) from payments where source <> 'INTERNAL' and paid_at >= ? group by 1, 2""", rs -> {
            YearMonth ym = YearMonth.of(rs.getInt(1), rs.getInt(2));
            perMonth.put(ym, rs.getBigDecimal(3));
            perMonthCount.put(ym, rs.getLong(4));
        }, since.atStartOfDay(ZONE).toOffsetDateTime());
        List<Amount> monthly = perMonth.entrySet().stream().map(e -> new Amount(e.getKey().toString(),
                e.getKey().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + (e.getKey().getMonthValue() == 1 ? " " + e.getKey().getYear() : ""),
                e.getValue(), perMonthCount.get(e.getKey()))).toList();
        List<Amount> byPurpose = jdbc.query("""
                select i.purpose, sum(p.amount), count(*) from payments p join invoices i on i.id = p.invoice_id
                where p.source <> 'INTERNAL' and p.paid_at >= ? group by i.purpose order by 2 desc""",
                (r, i) -> new Amount(r.getString(1), purposeLabel(r.getString(1)), r.getBigDecimal(2), r.getLong(3)), since.atStartOfDay(ZONE).toOffsetDateTime());
        BigDecimal thisMonth = perMonth.get(now);
        BigDecimal todays = jdbc.queryForObject("select coalesce(sum(amount), 0) from payments where source <> 'INTERNAL' and paid_at >= ?", BigDecimal.class,
                today.atStartOfDay(ZONE).toOffsetDateTime());

        Work work = work(today, yid);
        return new Dashboard(years, yid, year == null ? null : year.code(), billed, paid, sponsor, waived, billed.subtract(paid), percent, targets, current, onTrack,
                rows, ageing, top, monthly, byPurpose, thisMonth, todays, work, next(year, targets, current, percent, onTrack, work, ageing));
    }

    private Work work(LocalDate today, Long yid) {
        long exceptions = n(jdbc.queryForObject("select count(*) from gepg_reconciliation_items where status = 'OPEN'", Long.class));
        LocalDate lastSigned = jdbc.queryForList("select max(month) from reconciliation_signoffs", LocalDate.class).stream().filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        LocalDate lastMonth = YearMonth.from(today).minusMonths(1).atDay(1);
        boolean lastHadPayments = n(jdbc.queryForObject("select count(*) from payments where source = 'GEPG' and paid_at >= ? and paid_at < ?", Long.class,
                lastMonth.atStartOfDay(ZONE).toOffsetDateTime(), lastMonth.plusMonths(1).atStartOfDay(ZONE).toOffsetDateTime())) > 0;
        var open = adjustments.list(true);
        long forMe = open.stream().filter(a -> a.canApprove()).count();
        long refunds = n(jdbc.queryForObject("select count(*) from finance_adjustments where kind = 'REFUND' and status = 'APPROVED'", Long.class));
        Map<String, Object> sp = jdbc.queryForMap("""
                select count(*) as n, coalesce(sum(total_amount - paid_amount), 0) as owed from invoices
                where purpose = 'SPONSOR_FEES' and status in ('ISSUED', 'PARTIALLY_PAID')""");
        BigDecimal notInvoiced = yid == null ? BigDecimal.ZERO : notInvoicedShares(yid);
        long failures = n(jdbc.queryForObject("select count(*) from control_numbers where status = 'FAILED' and created_at >= now() - interval '7 days'", Long.class));
        return new Work(exceptions, lastSigned, !lastHadPayments || (lastSigned != null && !lastSigned.isBefore(lastMonth)), open.size(),
                open.stream().map(a -> a.amount()).reduce(BigDecimal.ZERO, BigDecimal::add), forMe, refunds, n(sp.get("n")), (BigDecimal) sp.get("owed"),
                notInvoiced, failures);
    }

    /** Sponsors' shares of the year's annual invoices that have not been invoiced to the sponsors yet. */
    private BigDecimal notInvoicedShares(Long yid) {
        List<Map<String, Object>> list = jdbc.queryForList("""
                select x.coverage_percent, x.cap_amount, x.fee_item_codes, i.id as invoice_id, i.total_amount - i.paid_amount as balance,
                       (select coalesce(sum(l.amount), 0) from invoice_lines l join invoices si on si.id = l.invoice_id
                         where l.beneficiary_invoice_id = i.id and si.status not in ('CANCELLED', 'CREDITED')) as invoiced,
                       (select coalesce(sum(l.amount - l.paid_amount), 0) from invoice_lines l join invoices si on si.id = l.invoice_id
                         where l.beneficiary_invoice_id = i.id and si.status not in ('CANCELLED', 'CREDITED')) as pending
                from sponsorships x join invoices i on i.payer_type = 'STUDENT' and i.payer_id = x.student_id and i.purpose = 'ANNUAL_FEES'
                     and i.academic_year_id = x.academic_year_id and i.status not in ('CANCELLED', 'CREDITED')
                where x.status = 'ACTIVE' and x.academic_year_id = ?""", yid);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> m : list) {
            String codes = (String) m.get("fee_item_codes");
            Set<String> items = codes == null || codes.isBlank() ? Set.of() : java.util.Arrays.stream(codes.split(",")).map(String::trim).collect(Collectors.toSet());
            BigDecimal base = jdbc.query("select fee_item_code, amount from invoice_lines where invoice_id = ?", (r, i) -> items.isEmpty() || items.contains(r.getString(1).toUpperCase())
                    ? r.getBigDecimal(2) : BigDecimal.ZERO, ((Number) m.get("invoice_id")).longValue()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal share = base.multiply((BigDecimal) m.get("coverage_percent")).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (m.get("cap_amount") != null) share = share.min((BigDecimal) m.get("cap_amount"));
            BigDecimal owed = ((BigDecimal) m.get("balance")).subtract((BigDecimal) m.get("pending"));   // never more than the student still owes
            total = total.add(share.subtract((BigDecimal) m.get("invoiced")).min(owed).max(BigDecimal.ZERO));
        }
        return total;
    }

    private static List<Step> next(YearOption year, List<Milestone> targets, Milestone current, BigDecimal percent, Boolean onTrack, Work w,
                                   List<Amount> ageing) {
        List<Step> next = new ArrayList<>();
        boolean bursar = has(Role.BURSAR, Role.INSTITUTION_ADMIN);
        if (w.controlNumberFailures() > 0) next.add(new Step("error", w.controlNumberFailures() + " GePG control number request(s) failed in the last 7 days.", "/staff/finance"));
        if (w.adjustmentsForMe() > 0) next.add(new Step("warning", w.adjustmentsForMe() + " waiver or refund request(s) wait for your approval.", "/staff/waivers"));
        if (w.reconciliationExceptions() > 0) next.add(new Step("warning", w.reconciliationExceptions() + " GePG reconciliation exception(s) are open.", "/staff/finance"));
        if (bursar && !w.lastMonthSignedOff()) next.add(new Step("info", "Last month's reconciliation is not signed off yet.", "/staff/finance"));
        if (year != null && targets.isEmpty() && bursar) next.add(new Step("info", "Set collection targets for " + year.code() + " to follow collections against them.", null));
        if (Boolean.FALSE.equals(onTrack)) {
            next.add(new Step("warning", "Collections are at " + percent.stripTrailingZeros().toPlainString() + "% of annual fees, behind the "
                    + current.percent().stripTrailingZeros().toPlainString() + "% target due " + current.dueDate() + ".", "/staff/reports"));
        }
        if (w.sponsorNotInvoiced().signum() > 0) next.add(new Step("info", "TZS " + money(w.sponsorNotInvoiced()) + " of sponsors' shares is not invoiced to the sponsors yet.", "/staff/sponsors"));
        if (w.sponsorInvoicesOpen() > 0) next.add(new Step("info", w.sponsorInvoicesOpen() + " sponsor invoice(s) are unpaid (TZS " + money(w.sponsorOutstanding()) + ").", "/staff/sponsors"));
        if (w.refundsToPay() > 0) next.add(new Step("info", w.refundsToPay() + " approved refund(s) to pay out and record.", "/staff/waivers"));
        ageing.stream().filter(a -> "OVER_90".equals(a.key()) && a.count() > 0).findFirst()
                .ifPresent(a -> next.add(new Step("info", a.count() + " invoice(s) are over 90 days overdue (TZS " + money(a.amount()) + ").", "/staff/reports")));
        if (next.isEmpty()) next.add(new Step("success", "Nothing is waiting in the bursary.", null));
        return next;
    }

    // ------------------------------------------------------------------ targets

    @Transactional(readOnly = true)
    public List<TargetDto> targets(Long academicYearId) {
        return jdbc.query("select due_date, percent from finance_targets where academic_year_id = ? order by due_date",
                (r, i) -> new TargetDto(r.getObject(1, LocalDate.class), r.getBigDecimal(2)), academicYearId);
    }

    @Transactional
    public List<TargetDto> saveTargets(Long academicYearId, List<TargetDto> list) {
        if (!has(Role.BURSAR, Role.INSTITUTION_ADMIN)) throw new AccessDeniedException("Only the Bursar sets collection targets");
        if (list.size() > 12) throw new BusinessRuleException("TOO_MANY_TARGETS", "Set at most 12 milestones");
        BigDecimal last = BigDecimal.ZERO;
        LocalDate lastDate = null;
        for (TargetDto t : list.stream().sorted(java.util.Comparator.comparing(TargetDto::dueDate)).toList()) {
            if (t.dueDate() == null || t.percent() == null || t.percent().signum() <= 0 || t.percent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessRuleException("INVALID_TARGET", "Each milestone needs a date and a percentage between 0 and 100");
            }
            if (t.dueDate().equals(lastDate)) throw new BusinessRuleException("INVALID_TARGET", "Two milestones fall on " + t.dueDate());
            if (t.percent().compareTo(last) < 0) throw new BusinessRuleException("INVALID_TARGET", "Milestones must not go down over time");
            last = t.percent();
            lastDate = t.dueDate();
        }
        jdbc.update("delete from finance_targets where academic_year_id = ?", academicYearId);
        for (TargetDto t : list) {
            jdbc.update("insert into finance_targets (tenant_id, academic_year_id, due_date, percent) values (cims_current_tenant(), ?, ?, ?)",
                    academicYearId, t.dueDate(), t.percent());
        }
        audit.record("FINANCE_TARGETS_SET", "AcademicYear", academicYearId, Map.of("milestones", list.size()));
        return targets(academicYearId);
    }

    // ------------------------------------------------------------------ postings for the accounting system (FR-FIN-11)

    /** Summarised postings by month, source and fee item for a period, as CSV for MUSE or the accounting system. */
    @Transactional
    public String postingsCsv(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new BusinessRuleException("INVALID_RANGE", "The end date is before the start date");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw new BusinessRuleException("RANGE_TOO_LONG", "Choose a period of at most one year");
        var f = from.atStartOfDay(ZONE).toOffsetDateTime();
        var t = to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        StringBuilder sb = new StringBuilder(Csv.line("Month", "Source", "Fee item", "Description", "Amount", "Allocations"));
        long rows = 0;
        for (Map<String, Object> m : jdbc.queryForList("""
                select to_char(p.paid_at at time zone 'Africa/Dar_es_Salaam', 'YYYY-MM') as month, p.source,
                       case when p.source = 'INTERNAL' then p.channel else p.source end as src, l.fee_item_code, max(l.description) as description,
                       sum(a.amount) as amount, count(*) as n
                from payment_allocations a join payments p on p.id = a.payment_id join invoice_lines l on l.id = a.invoice_line_id
                where p.paid_at >= ? and p.paid_at < ? group by 1, 2, 3, 4 order by 1, 4, 3""", f, t)) {
            sb.append(Csv.line(m.get("month"), m.get("src"), m.get("fee_item_code"), m.get("description"), m.get("amount"), m.get("n")));
            rows++;
        }
        for (Map<String, Object> m : jdbc.queryForList("""
                select to_char(paid_at at time zone 'Africa/Dar_es_Salaam', 'YYYY-MM') as month, source, sum(unallocated_amount) as credit, count(*) as n
                from payments where paid_at >= ? and paid_at < ? and unallocated_amount > 0 group by 1, 2 order by 1""", f, t)) {
            sb.append(Csv.line(m.get("month"), m.get("source"), "CREDIT", "Unallocated (held as credit)", m.get("credit"), m.get("n")));
            rows++;
        }
        audit.record("POSTINGS_EXPORTED", "Report", null, Map.of("from", from.toString(), "to", to.toString(), "rows", rows));
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static String purposeLabel(String p) {
        return switch (p) {
            case "ANNUAL_FEES" -> "Annual fees";
            case "APPLICATION_FEE" -> "Application fees";
            case "HOSTEL_FEES" -> "Hostel fees";
            case "RESIT_FEES" -> "Supplementary / special exams";
            case "APPEAL_FEES" -> "Remarks and appeals";
            case "SPONSOR_FEES" -> "Sponsors";
            case "HOSTEL_DAMAGES" -> "Hostel damages";
            default -> p.charAt(0) + p.substring(1).toLowerCase().replace('_', ' ');
        };
    }

    private static boolean has(Role... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Set<String> mine = auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        for (Role r : roles) if (mine.contains(r.authority())) return true;
        return false;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> l) {
        return l.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long n(Object o) {
        return o instanceof Number x ? x.longValue() : 0;
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.0f", v);
    }
}
