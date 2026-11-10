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
 * SMS (and email to a verified address) a few days before and on the due date; while it is overdue, again every few days up to a
 * set number of times. From the second overdue reminder the student's guardians are told too, and a sponsor whose share is still
 * unpaid is reminded of that share. A student may opt out of reminder SMS; the guardians and sponsors are still told. After the
 * grace period a penalty line is added to the invoice once per instalment (it can be waived through the usual waiver approval).
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
    private final tz.co.msingi.cims.portal.family.GuardianLinks guardianLinks;

    public record DueDate(@Min(1) @Max(12) int instalment, @NotNull LocalDate dueDate) {
    }

    public record DueDatesRequest(@NotNull List<DueDate> dates) {
    }

    /** The collection rules; the reminder fields left out of a request keep their current value. */
    public record Rules(boolean remindersEnabled, @Min(1) @Max(60) int reminderDaysBefore, @NotNull @Pattern(regexp = "NONE|PERCENT|FIXED") String penaltyMode,
                        @NotNull @DecimalMin("0") BigDecimal penaltyValue, @Min(0) @Max(180) int penaltyGraceDays,
                        @Min(1) @Max(60) Integer overdueRepeatDays, @Min(1) @Max(10) Integer overdueMaxReminders, Boolean remindGuardians,
                        Boolean remindSponsors) {
        public Rules(boolean remindersEnabled, int reminderDaysBefore, String penaltyMode, BigDecimal penaltyValue, int penaltyGraceDays) {
            this(remindersEnabled, reminderDaysBefore, penaltyMode, penaltyValue, penaltyGraceDays, null, null, null, null);
        }
    }

    /** One reminder sent (or recorded as not sent, channel NONE, for a student who opted out). */
    public record ReminderView(Long id, OffsetDateTime sentAt, String invoiceNo, String payerName, String registrationNo, int instalment, String kind, int round,
                               String recipient, String channel, String sentTo, BigDecimal amount) {
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
        List<Rules> r = jdbc.query("""
                select reminders_enabled, reminder_days_before, penalty_mode, penalty_value, penalty_grace_days, overdue_repeat_days, overdue_max_reminders,
                       remind_guardians, remind_sponsors from finance_settings""",
                (rs, i) -> new Rules(rs.getBoolean(1), rs.getInt(2), rs.getString(3), rs.getBigDecimal(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getBoolean(8), rs.getBoolean(9)));
        return r.isEmpty() ? new Rules(true, 7, "NONE", BigDecimal.ZERO, 14, 7, 3, true, true) : r.get(0);
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
        r = new Rules(r.remindersEnabled(), r.reminderDaysBefore(), r.penaltyMode(), r.penaltyValue(), r.penaltyGraceDays(),
                r.overdueRepeatDays() != null ? r.overdueRepeatDays() : before.overdueRepeatDays(),
                r.overdueMaxReminders() != null ? r.overdueMaxReminders() : before.overdueMaxReminders(),
                r.remindGuardians() != null ? r.remindGuardians() : before.remindGuardians(),
                r.remindSponsors() != null ? r.remindSponsors() : before.remindSponsors());
        int n = jdbc.update("update finance_settings set reminders_enabled = ?, reminder_days_before = ?, penalty_mode = ?, penalty_value = ?, "
                + "penalty_grace_days = ?, updated_at = now()", r.remindersEnabled(), r.reminderDaysBefore(), r.penaltyMode(), r.penaltyValue(), r.penaltyGraceDays());
        if (n == 0) {
            jdbc.update("insert into finance_settings (tenant_id, reminders_enabled, reminder_days_before, penalty_mode, penalty_value, penalty_grace_days) "
                    + "values (cims_current_tenant(), ?, ?, ?, ?, ?)", r.remindersEnabled(), r.reminderDaysBefore(), r.penaltyMode(), r.penaltyValue(), r.penaltyGraceDays());
        }
        jdbc.update("update finance_settings set overdue_repeat_days = ?, overdue_max_reminders = ?, remind_guardians = ?, remind_sponsors = ?",
                r.overdueRepeatDays(), r.overdueMaxReminders(), r.remindGuardians(), r.remindSponsors());
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
                        if (days == rules.reminderDaysBefore()) reminders += remind(inv, instalment, "BEFORE", 1, thisOne, date, rules);
                        else if (days == 0) reminders += remind(inv, instalment, "DUE", 1, thisOne, date, rules);
                        else if (days < 0) {
                            // overdue: round 1 the day after, then every overdueRepeatDays, up to overdueMaxReminders; never a backlog at once
                            int round = (int) Math.min(rules.overdueMaxReminders(), 1 + (-days - 1) / rules.overdueRepeatDays());
                            reminders += remind(inv, instalment, "OVERDUE", round, thisOne, date, rules);
                            BigDecimal share = sponsor == null ? BigDecimal.ZERO : sponsor;
                            if (rules.remindSponsors() && share.signum() > 0) reminders += remindSponsor(inv, instalment, round, share, date);
                        }
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

    /**
     * The reminders of one round to the payer (unless a student opted out), and from the second overdue round to the student's guardians.
     * Each (invoice, instalment, kind, round, recipient, channel) is sent once, however often the job runs. Returns how many were sent.
     */
    private int remind(Invoice inv, int instalment, String kind, int round, BigDecimal amount, LocalDate date, Rules rules) {
        ControlNumber cn = billing.currentControlNumber(inv.getId());
        String control = cn == null || cn.getStatus() == ControlNumber.Status.PAID ? "-" : cn.getControlNo();
        String when = switch (kind) {
            case "BEFORE" -> "is due on " + DAY.format(date);
            case "DUE" -> "is due today";
            default -> "was due on " + DAY.format(date) + " and is overdue";
        };
        int sent = 0;
        Map<String, Object> student = "STUDENT".equals(String.valueOf(inv.getPayerType())) ? jdbc.queryForList(
                "select id, registration_no, concat_ws(' ', first_name, surname) as name, fee_reminders_opt_out from students where id = ?",
                inv.getPayerId()).stream().findFirst().orElse(null) : null;
        boolean optedOut = student != null && Boolean.TRUE.equals(student.get("fee_reminders_opt_out"));
        String phone = inv.getPayerPhone() == null || inv.getPayerPhone().isBlank() ? null : inv.getPayerPhone();
        if (record(inv, instalment, kind, round, "PAYER", optedOut || phone == null ? "NONE" : "SMS", optedOut ? "opted out" : phone, amount)
                && !optedOut && phone != null) {
            messaging.event("FEE_REMINDER", phone, Map.of("amount", "TZS " + tzs(amount), "instalment", String.valueOf(instalment),
                    "when", when, "controlNo", control, "description", inv.getDescription()));
            sent++;
        }
        if (student != null && rules.remindGuardians() && "OVERDUE".equals(kind) && round >= 2) {
            guardianLinks.linkNextOfKin(((Number) student.get("id")).longValue());
            for (Map<String, Object> g : jdbc.queryForList("select distinct phone from guardian_links where student_id = ? and active", student.get("id"))) {
                String gp = (String) g.get("phone");
                if (gp.equals(phone)) continue;
                if (record(inv, instalment, kind, round, "GUARDIAN", "SMS", gp, amount)) {
                    messaging.event("FEE_REMINDER_GUARDIAN", gp, Map.of("student", (String) student.get("name"), "amount", "TZS " + tzs(amount),
                            "when", when, "controlNo", control));
                    sent++;
                }
            }
        }
        return sent;
    }

    /** A sponsor whose committed share for the student is still unpaid after the due date: by SMS and email to its contact. */
    private int remindSponsor(Invoice inv, int instalment, int round, BigDecimal share, LocalDate date) {
        if (!"STUDENT".equals(String.valueOf(inv.getPayerType()))) return 0;
        List<Map<String, Object>> sp = jdbc.queryForList("""
                select sp.name, sp.contact_phone, sp.contact_email, s.registration_no, concat_ws(' ', s.first_name, s.surname) as student
                from sponsorships x join sponsors sp on sp.id = x.sponsor_id join students s on s.id = x.student_id
                where x.student_id = ? and x.academic_year_id = ? and x.status = 'ACTIVE' and sp.active""", inv.getPayerId(), inv.getAcademicYearId());
        int sent = 0;
        for (Map<String, Object> r : sp) {
            Map<String, String> v = Map.of("sponsor", (String) r.get("name"), "student", (String) r.get("student"), "registrationNo", (String) r.get("registration_no"),
                    "amount", "TZS " + tzs(share), "when", "was due on " + DAY.format(date));
            String phone = (String) r.get("contact_phone");
            if (phone != null && !phone.isBlank() && record(inv, instalment, "OVERDUE", round, "SPONSOR", "SMS", phone, share)) {
                messaging.event("FEE_REMINDER_SPONSOR", phone, v);
                sent++;
            }
            String mail = (String) r.get("contact_email");
            if (mail != null && !mail.isBlank() && record(inv, instalment, "OVERDUE", round, "SPONSOR", "EMAIL", mail, share)) {
                messaging.emailEvent("FEE_REMINDER_SPONSOR", mail, v);
                sent++;
            }
        }
        return sent;
    }

    private boolean record(Invoice inv, int instalment, String kind, int round, String recipient, String channel, String to, BigDecimal amount) {
        return jdbc.update("""
                insert into fee_reminders (tenant_id, invoice_id, instalment, kind, amount, round, recipient, channel, sent_to)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (invoice_id, instalment, kind, round, recipient, channel) do nothing""",
                inv.getId(), instalment, kind, amount, round, recipient, channel, to == null ? null : to.length() > 120 ? to.substring(0, 120) : to) > 0;
    }

    /** The reminders of the last days, newest first (Bursar, Accountant). */
    @Transactional(readOnly = true)
    public List<ReminderView> reminders(int days) {
        return jdbc.query("""
                select r.id, r.sent_at, i.invoice_no, i.payer_name, s.registration_no, r.instalment, r.kind, r.round, r.recipient, r.channel, r.sent_to, r.amount
                from fee_reminders r join invoices i on i.id = r.invoice_id
                left join students s on i.payer_type = 'STUDENT' and s.id = i.payer_id
                where r.sent_at >= now() - make_interval(days => ?) order by r.sent_at desc, r.id desc limit 500""",
                (rs, i) -> new ReminderView(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10), mask(rs.getString(11)), rs.getBigDecimal(12)),
                Math.max(1, Math.min(days, 366)));
    }

    /** A phone shown as 2557•••••123; an email with its local part shortened. */
    static String mask(String to) {
        if (to == null) return null;
        if (to.contains("@")) {
            int at = to.indexOf('@');
            return (at <= 2 ? to.substring(0, Math.min(1, at)) : to.substring(0, 2)) + "•••" + to.substring(at);
        }
        if (to.length() < 8) return to;
        return to.substring(0, 4) + "•••••" + to.substring(to.length() - 3);
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
