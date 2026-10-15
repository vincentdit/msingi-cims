package tz.co.msingi.cims.finance.instalments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.InvoiceLine;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Instalment plans, reminders and late-payment penalties (BR-24, FR-FIN-07). The Bursar sets when each instalment of an academic
 * year falls due, how many days before to remind, and the penalty rule. Each morning payers with an unpaid instalment are reminded by
 * SMS a few days before, on the due date and once when it is overdue; after the grace period a penalty line is added to the invoice once
 * per instalment (it can be waived through the usual waiver approval). Sponsor shares not yet paid are not chased.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstalmentService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    static final String PURPOSE = "ANNUAL_FEES";

    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final Messaging messaging;
    private final AuditService audit;
    private final jakarta.persistence.EntityManager entityManager;

    public record DueDate(@Min(1) @Max(12) int instalment, @NotNull LocalDate dueDate) {
    }

    public record DueDatesRequest(@NotNull List<DueDate> dates) {
    }

    public record Rules(boolean remindersEnabled, @Min(1) @Max(60) int reminderDaysBefore, @NotNull @Pattern(regexp = "NONE|PERCENT|FIXED") String penaltyMode,
                        @NotNull @DecimalMin("0") BigDecimal penaltyValue, @Min(0) @Max(180) int penaltyGraceDays) {
    }

    public record RunResult(LocalDate date, int reminders, int penalties, BigDecimal penaltyTotal) {
    }

    public record PenaltyView(Long id, Long invoiceId, String invoiceNo, String payerName, int instalment, BigDecimal overdueAmount,
                              BigDecimal amount, String rule, OffsetDateTime appliedAt) {
    }

    // ------------------------------------------------------------------ plan and rules

    @Transactional(readOnly = true)
    public List<DueDate> dueDates(Long academicYearId) {
        return jdbc.query("select instalment, due_date from instalment_due_dates where academic_year_id = ? order by instalment",
                (rs, i) -> new DueDate(rs.getInt(1), rs.getObject(2, LocalDate.class)), academicYearId);
    }

    @Transactional
    public List<DueDate> setDueDates(Long academicYearId, List<DueDate> dates) {
        Integer exists = jdbc.queryForObject("select count(*) from academic_years where id = ?", Integer.class, academicYearId);
        if (exists == null || exists == 0) throw new NotFoundException("Academic year", academicYearId);
        Set<Integer> seen = new HashSet<>();
        LocalDate previous = null;
        List<DueDate> sorted = dates.stream().sorted(java.util.Comparator.comparingInt(DueDate::instalment)).toList();
        for (DueDate d : sorted) {
            if (!seen.add(d.instalment())) throw new BusinessRuleException("DUPLICATE_INSTALMENT", "Instalment " + d.instalment() + " is listed twice");
            if (previous != null && !d.dueDate().isAfter(previous)) {
                throw new BusinessRuleException("DATES_OUT_OF_ORDER", "Instalment " + d.instalment() + " must fall due after the one before it");
            }
            previous = d.dueDate();
        }
        jdbc.update("delete from instalment_due_dates where academic_year_id = ?", academicYearId);
        for (DueDate d : sorted) {
            jdbc.update("insert into instalment_due_dates (tenant_id, academic_year_id, instalment, due_date) values (cims_current_tenant(), ?, ?, ?)",
                    academicYearId, d.instalment(), d.dueDate());
        }
        audit.record("INSTALMENT_DATES_SET", "AcademicYear", academicYearId, Map.of("dates", sorted.toString()));
        return dueDates(academicYearId);
    }

    @Transactional(readOnly = true)
    public Rules rules() {
        List<Rules> r = jdbc.query("select reminders_enabled, reminder_days_before, penalty_mode, penalty_value, penalty_grace_days from finance_settings",
                (rs, i) -> new Rules(rs.getBoolean(1), rs.getInt(2), rs.getString(3), rs.getBigDecimal(4), rs.getInt(5)));
        return r.isEmpty() ? new Rules(true, 7, "NONE", BigDecimal.ZERO, 14) : r.get(0);
    }

    @Transactional
    public Rules setRules(Rules r) {
        if ("PERCENT".equals(r.penaltyMode()) && r.penaltyValue().compareTo(new BigDecimal("50")) > 0) {
            throw new BusinessRuleException("PENALTY_TOO_HIGH", "A percentage penalty can be at most 50%");
        }
        if (!"NONE".equals(r.penaltyMode()) && r.penaltyValue().signum() <= 0) {
            throw new BusinessRuleException("PENALTY_VALUE_REQUIRED", "Give the penalty amount or percentage");
        }
        Rules before = rules();
        int n = jdbc.update("update finance_settings set reminders_enabled = ?, reminder_days_before = ?, penalty_mode = ?, penalty_value = ?, "
                + "penalty_grace_days = ?, updated_at = now()", r.remindersEnabled(), r.reminderDaysBefore(), r.penaltyMode(), r.penaltyValue(), r.penaltyGraceDays());
        if (n == 0) {
            jdbc.update("insert into finance_settings (tenant_id, reminders_enabled, reminder_days_before, penalty_mode, penalty_value, penalty_grace_days) "
                    + "values (cims_current_tenant(), ?, ?, ?, ?, ?)", r.remindersEnabled(), r.reminderDaysBefore(), r.penaltyMode(), r.penaltyValue(), r.penaltyGraceDays());
        }
        audit.record("COLLECTION_RULES_SET", "FinanceSettings", null, Map.of("before", before.toString(), "after", r.toString()));
        return rules();
    }

    @Transactional(readOnly = true)
    public List<PenaltyView> penalties() {
        return jdbc.query("""
                select p.id, p.invoice_id, i.invoice_no, i.payer_name, p.instalment, p.overdue_amount, p.amount, p.rule, p.applied_at
                from fee_penalties p join invoices i on i.id = p.invoice_id order by p.applied_at desc limit 300""",
                (rs, i) -> new PenaltyView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class)));
    }

    // ------------------------------------------------------------------ the morning run

    /** Reminders and penalties for the given day. Safe to run more than once a day: each reminder and penalty happens once. */
    @Transactional
    public RunResult run(LocalDate today) {
        Rules rules = rules();
        Map<Long, Map<Integer, LocalDate>> plan = new LinkedHashMap<>();
        jdbc.query("select academic_year_id, instalment, due_date from instalment_due_dates", rs -> {
            plan.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getInt(2), rs.getObject(3, LocalDate.class));
        });
        int reminders = 0, penalties = 0;
        BigDecimal penaltyTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, Map<Integer, LocalDate>> year : plan.entrySet()) {
            for (Invoice inv : invoices.openByPurpose(PURPOSE, year.getKey())) {
                BigDecimal sponsor = invoices.sponsorPending(inv.getId());
                BigDecimal sponsorLeft = sponsor == null ? BigDecimal.ZERO : sponsor;
                for (Map.Entry<Integer, LocalDate> due : year.getValue().entrySet()) {
                    int instalment = due.getKey();
                    // what is still owed on this instalment and the ones before it, less what a sponsor will pay
                    BigDecimal owed = owedUpTo(inv, instalment).subtract(sponsorLeft).max(BigDecimal.ZERO);
                    BigDecimal thisOne = owed.min(owedOn(inv, instalment));
                    if (thisOne.signum() <= 0) continue;
                    LocalDate date = due.getValue();
                    long days = java.time.temporal.ChronoUnit.DAYS.between(today, date);   // positive = before the due date
                    if (rules.remindersEnabled()) {
                        String kind = days == rules.reminderDaysBefore() ? "BEFORE" : days == 0 ? "DUE" : days < 0 && -days >= 1 ? "OVERDUE" : null;
                        if (kind != null && remind(inv, instalment, kind, thisOne, date)) reminders++;
                    }
                    if (!"NONE".equals(rules.penaltyMode()) && -days > rules.penaltyGraceDays()) {
                        BigDecimal p = penalise(inv, instalment, thisOne, rules);
                        if (p != null) {
                            penalties++;
                            penaltyTotal = penaltyTotal.add(p);
                        }
                    }
                }
            }
        }
        if (reminders + penalties > 0) log.info("Fee run {}: {} reminder(s), {} penalty(ies)", today, reminders, penalties);
        return new RunResult(today, reminders, penalties, penaltyTotal);
    }

    private boolean remind(Invoice inv, int instalment, String kind, BigDecimal amount, LocalDate date) {
        int inserted = jdbc.update("""
                insert into fee_reminders (tenant_id, invoice_id, instalment, kind, amount) values (cims_current_tenant(), ?, ?, ?, ?)
                on conflict (invoice_id, instalment, kind) do nothing""", inv.getId(), instalment, kind, amount);
        if (inserted == 0 || inv.getPayerPhone() == null || inv.getPayerPhone().isBlank()) return inserted > 0;
        ControlNumber cn = billing.currentControlNumber(inv.getId());
        String when = switch (kind) {
            case "BEFORE" -> "is due on " + DAY.format(date);
            case "DUE" -> "is due today";
            default -> "was due on " + DAY.format(date) + " and is overdue";
        };
        messaging.event("FEE_REMINDER", inv.getPayerPhone(), Map.of("amount", "TZS " + tzs(amount), "instalment", String.valueOf(instalment),
                "when", when, "controlNo", cn == null || cn.getStatus() == ControlNumber.Status.PAID ? "-" : cn.getControlNo(),
                "description", inv.getDescription()));
        return true;
    }

    private BigDecimal penalise(Invoice inv, int instalment, BigDecimal overdue, Rules rules) {
        Integer done = jdbc.queryForObject("select count(*) from fee_penalties where invoice_id = ? and instalment = ?", Integer.class, inv.getId(), instalment);
        if (done != null && done > 0) return null;
        BigDecimal amount = "PERCENT".equals(rules.penaltyMode())
                ? overdue.multiply(rules.penaltyValue()).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP)
                : rules.penaltyValue().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) return null;
        String rule = "PERCENT".equals(rules.penaltyMode()) ? tzs(rules.penaltyValue()) + "% of TZS " + tzs(overdue) : "fixed TZS " + tzs(amount);
        InvoiceLine line = inv.addLine("PENALTY", "Late payment penalty — instalment " + instalment, amount);
        line.setPriority((short) 999);    // payments clear fees before penalties
        line.setInstalment((short) instalment);
        entityManager.persist(line);
        entityManager.flush();
        jdbc.update("""
                insert into fee_penalties (tenant_id, invoice_id, instalment, overdue_amount, amount, rule)
                values (cims_current_tenant(), ?, ?, ?, ?, ?)""", inv.getId(), instalment, overdue, amount, rule);
        audit.recordAs(null, "CIMS", "LATE_PAYMENT_PENALTY", "Invoice", inv.getId(), Map.of("invoiceNo", inv.getInvoiceNo(),
                "instalment", instalment, "overdue", overdue, "penalty", amount, "rule", rule));
        if (inv.getPayerPhone() != null && !inv.getPayerPhone().isBlank()) {
            messaging.event("FEE_PENALTY", inv.getPayerPhone(), Map.of("amount", "TZS " + tzs(amount), "instalment", String.valueOf(instalment),
                    "description", inv.getDescription()));
        }
        return amount;
    }

    private static BigDecimal owedOn(Invoice inv, int instalment) {
        return inv.getLines().stream().filter(l -> l.getInstalment() == instalment && !"PENALTY".equals(l.getFeeItemCode()))
                .map(l -> l.getAmount().subtract(l.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO);
    }

    private static BigDecimal owedUpTo(Invoice inv, int instalment) {
        return inv.getLines().stream().filter(l -> l.getInstalment() <= instalment && !"PENALTY".equals(l.getFeeItemCode()))
                .map(l -> l.getAmount().subtract(l.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static String tzs(BigDecimal v) {
        BigDecimal a = v.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", a.longValue()) : String.format(Locale.ENGLISH, "%,.2f", a);
    }

}
