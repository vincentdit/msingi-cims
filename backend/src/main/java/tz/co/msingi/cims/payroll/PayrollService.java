package tz.co.msingi.cims.payroll;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.hr.StaffService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Payroll (FR-PAY-01..05): the statutory rates, each person's pay details with standing allowances and deductions, the monthly
 * payroll (prepared by HR or the Accountant, approved by the Bursar or the Principal — never by whoever prepared it — then paid),
 * the payroll workbook with the PAYE, pension, NHIF, HESLB, WCF and SDL schedules, the bank file, and payslips that staff read in CIMS.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] PREPARE = {Role.INSTITUTION_ADMIN, Role.HR_OFFICER, Role.ACCOUNTANT};
    static final Role[] APPROVE = {Role.INSTITUTION_ADMIN, Role.BURSAR, Role.MANAGEMENT_VIEWER};
    static final Role[] PAY = {Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT};
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExamAccess access;
    private final StaffService staff;
    private final Messaging messaging;
    private final AuditService audit;

    public record Settings(String pensionFund, BigDecimal pensionEmployeeRate, BigDecimal pensionEmployerRate, boolean pensionOnBasic,
                           List<PayCalc.Band> payeBands, BigDecimal wcfRate, boolean sdlApplies, BigDecimal sdlRate, boolean nhifApplies,
                           BigDecimal nhifEmployeeRate, BigDecimal nhifEmployerRate, BigDecimal heslbRate, String updatedByName, OffsetDateTime updatedAt) {
    }

    public record PayItem(Long id, String kind, String name, BigDecimal amount, boolean taxable, BigDecimal balance, boolean active) {
    }

    public record PayDetails(Long staffId, String staffNo, String fullName, String jobTitle, String department, boolean onPayroll, BigDecimal basicSalary,
                             String bankName, String bankBranch, String bankAccount, String tin, String pensionNo, String nhifNo, boolean heslbLoan,
                             String heslbIndexNo, List<PayItem> items, PayCalc.Result preview) {
    }

    public record PayDetailsRequest(Boolean onPayroll, BigDecimal basicSalary, String bankName, String bankBranch, String bankAccount, String tin,
                                    String pensionNo, String nhifNo, Boolean heslbLoan, String heslbIndexNo) {
    }

    public record PayItemRequest(String kind, String name, BigDecimal amount, Boolean taxable, BigDecimal balance, Boolean active) {
    }

    public record PayStaffRow(Long staffId, String staffNo, String fullName, String department, String category, boolean onPayroll, BigDecimal basicSalary,
                              BigDecimal allowances, BigDecimal deductions, boolean bankReady) {
    }

    public record RunView(Long id, LocalDate period, String month, String status, int staffCount, BigDecimal grossTotal, BigDecimal netTotal,
                          BigDecimal employerTotal, String preparedByName, OffsetDateTime preparedAt, String approvedByName, OffsetDateTime approvedAt,
                          String paidByName, OffsetDateTime paidAt, LocalDate paidOn, boolean canApprove, String note) {
    }

    public record Slip(Long id, Long runId, String month, Long staffId, String staffNo, String fullName, String jobTitle, String department, String bankName,
                       String bankBranch, String bankAccount, String tin, String pensionNo, BigDecimal basic, BigDecimal taxableAllowances,
                       BigDecimal otherAllowances, BigDecimal gross, BigDecimal pensionEmployee, BigDecimal taxableIncome, BigDecimal paye,
                       BigDecimal nhifEmployee, BigDecimal heslb, BigDecimal otherDeductions, BigDecimal totalDeductions, BigDecimal net,
                       BigDecimal pensionEmployer, BigDecimal nhifEmployer, BigDecimal wcf, BigDecimal sdl, List<PayCalc.Line> lines, String warning) {
    }

    public record Totals(BigDecimal basic, BigDecimal gross, BigDecimal pensionEmployee, BigDecimal pensionEmployer, BigDecimal paye, BigDecimal nhifEmployee,
                         BigDecimal nhifEmployer, BigDecimal heslb, BigDecimal otherDeductions, BigDecimal net, BigDecimal wcf, BigDecimal sdl) {
    }

    public record RunDetail(RunView run, List<Slip> slips, Totals totals, List<String> problems) {
    }

    public record MySlip(Long id, String month, LocalDate period, BigDecimal gross, BigDecimal totalDeductions, BigDecimal net, LocalDate paidOn) {
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public Settings settings() {
        jdbc.update("insert into payroll_settings (tenant_id) values (cims_current_tenant()) on conflict (tenant_id) do nothing");
        return jdbc.queryForObject("""
                select pension_fund, pension_employee_rate, pension_employer_rate, pension_on_basic, paye_bands::text, wcf_rate, sdl_applies, sdl_rate,
                       nhif_applies, nhif_employee_rate, nhif_employer_rate, heslb_rate, updated_by_name, updated_at from payroll_settings""",
                (rs, n) -> new Settings(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBoolean(4), bands(rs.getString(5)), rs.getBigDecimal(6),
                        rs.getBoolean(7), rs.getBigDecimal(8), rs.getBoolean(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getString(13), odt(rs.getTimestamp(14))));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        access.require("change the payroll rates", Role.INSTITUTION_ADMIN, Role.HR_OFFICER, Role.BURSAR);
        settings();
        String fund = s.pensionFund() == null ? "" : s.pensionFund().toUpperCase(Locale.ROOT);
        if (!fund.equals("NSSF") && !fund.equals("PSSSF")) throw new BusinessRuleException("FUND_INVALID", "The pension fund is NSSF or PSSSF");
        for (BigDecimal r : Arrays.asList(s.pensionEmployeeRate(), s.pensionEmployerRate(), s.wcfRate(), s.sdlRate(), s.nhifEmployeeRate(),
                s.nhifEmployerRate(), s.heslbRate())) {
            if (r == null || r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessRuleException("RATE_INVALID", "Each rate is a percentage from 0 to 100");
            }
        }
        List<PayCalc.Band> bands = s.payeBands() == null ? List.of() : s.payeBands();
        if (bands.isEmpty() || bands.stream().noneMatch(b -> b.from() != null && b.from().signum() == 0)) {
            throw new BusinessRuleException("BANDS_INVALID", "The PAYE bands start from 0");
        }
        if (bands.stream().anyMatch(b -> b.from() == null || b.from().signum() < 0 || b.rate() == null || b.rate().signum() < 0
                || b.rate().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessRuleException("BANDS_INVALID", "Each band has a starting amount and a rate from 0 to 100");
        }
        if (bands.stream().map(b -> b.from().stripTrailingZeros()).distinct().count() != bands.size()) {
            throw new BusinessRuleException("BANDS_INVALID", "Two bands start at the same amount");
        }
        List<PayCalc.Band> sorted = bands.stream().sorted(java.util.Comparator.comparing(PayCalc.Band::from)).toList();
        jdbc.update("""
                update payroll_settings set pension_fund = ?, pension_employee_rate = ?, pension_employer_rate = ?, pension_on_basic = ?, paye_bands = ?::jsonb,
                  wcf_rate = ?, sdl_applies = ?, sdl_rate = ?, nhif_applies = ?, nhif_employee_rate = ?, nhif_employer_rate = ?, heslb_rate = ?,
                  updated_by_name = ?, updated_at = now()""", fund, s.pensionEmployeeRate(), s.pensionEmployerRate(), s.pensionOnBasic(), json(sorted),
                s.wcfRate(), s.sdlApplies(), s.sdlRate(), s.nhifApplies(), s.nhifEmployeeRate(), s.nhifEmployerRate(), s.heslbRate(), CurrentUser.name());
        audit.record("PAYROLL_RATES_CHANGED", "PayrollSettings", null, Map.of("fund", fund));
        return settings();
    }

    // ------------------------------------------------------------------ pay details

    @Transactional(readOnly = true)
    public List<PayStaffRow> staffList(String q) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select m.id, m.staff_no, m.full_name, d.name, m.category, coalesce(p.on_payroll, false), coalesce(p.basic_salary, 0),
                       (select coalesce(sum(amount), 0) from staff_pay_items i where i.staff_id = m.id and i.kind = 'ALLOWANCE' and i.active),
                       (select coalesce(sum(amount), 0) from staff_pay_items i where i.staff_id = m.id and i.kind = 'DEDUCTION' and i.active),
                       p.bank_account is not null and p.bank_name is not null
                from staff_members m left join staff_pay p on p.staff_id = m.id left join departments d on d.id = m.department_id
                where m.status = 'ACTIVE' and (lower(m.full_name) like ? or lower(m.staff_no) like ?) order by m.full_name""",
                (rs, n) -> new PayStaffRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                        rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBoolean(10)), like, like);
    }

    @Transactional
    public PayDetails payDetails(Long staffId) {
        Map<String, Object> m = jdbc.queryForList("""
                select m.id, m.staff_no, m.full_name, m.job_title, d.name as dept from staff_members m left join departments d on d.id = m.department_id
                where m.id = ?""", staffId).stream().findFirst().orElseThrow(() -> new NotFoundException("Staff record not found"));
        Map<String, Object> p = jdbc.queryForList("select * from staff_pay where staff_id = ?", staffId).stream().findFirst().orElse(Map.of());
        List<PayItem> items = items(staffId);
        BigDecimal basic = p.get("basic_salary") == null ? BigDecimal.ZERO : (BigDecimal) p.get("basic_salary");
        boolean heslb = Boolean.TRUE.equals(p.get("heslb_loan"));
        PayCalc.Result preview = PayCalc.compute(rates(settings()), basic, heslb, calcItems(items));
        return new PayDetails(staffId, (String) m.get("staff_no"), (String) m.get("full_name"), (String) m.get("job_title"), (String) m.get("dept"),
                !p.isEmpty() && Boolean.TRUE.equals(p.get("on_payroll")), basic, (String) p.get("bank_name"), (String) p.get("bank_branch"),
                (String) p.get("bank_account"), (String) p.get("tin"), (String) p.get("pension_no"), (String) p.get("nhif_no"), heslb,
                (String) p.get("heslb_index_no"), items, preview);
    }

    @Transactional
    public PayDetails savePayDetails(Long staffId, PayDetailsRequest r) {
        access.require("change pay details", PREPARE);
        exists(staffId);
        BigDecimal basic = r.basicSalary() == null ? BigDecimal.ZERO : r.basicSalary();
        if (basic.signum() < 0) throw new BusinessRuleException("SALARY_INVALID", "The basic salary cannot be negative");
        String account = cut(r.bankAccount(), 40);
        if (account != null && !account.replace(" ", "").matches("[0-9A-Za-z-]{5,40}")) {
            throw new BusinessRuleException("ACCOUNT_INVALID", "Give the bank account number (digits, letters and dashes)");
        }
        String tin = cut(r.tin(), 20);
        if (tin != null && !tin.replace("-", "").matches("\\d{9}")) throw new BusinessRuleException("TIN_INVALID", "A TIN has 9 digits, e.g. 123-456-789");
        if (Boolean.TRUE.equals(r.heslbLoan()) && cut(r.heslbIndexNo(), 30) == null) {
            throw new BusinessRuleException("HESLB_INDEX_REQUIRED", "Give the form four index number HESLB knows the loan by");
        }
        jdbc.update("""
                insert into staff_pay (staff_id, tenant_id, on_payroll, basic_salary, bank_name, bank_branch, bank_account, tin, pension_no, nhif_no, heslb_loan,
                  heslb_index_no, updated_by_name, updated_at)
                values (?, cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (staff_id) do update set on_payroll = excluded.on_payroll, basic_salary = excluded.basic_salary, bank_name = excluded.bank_name,
                  bank_branch = excluded.bank_branch, bank_account = excluded.bank_account, tin = excluded.tin, pension_no = excluded.pension_no,
                  nhif_no = excluded.nhif_no, heslb_loan = excluded.heslb_loan, heslb_index_no = excluded.heslb_index_no,
                  updated_by_name = excluded.updated_by_name, updated_at = now()""",
                staffId, !Boolean.FALSE.equals(r.onPayroll()), basic, cut(r.bankName(), 80), cut(r.bankBranch(), 80), account, tin, cut(r.pensionNo(), 30),
                cut(r.nhifNo(), 30), Boolean.TRUE.equals(r.heslbLoan()), cut(r.heslbIndexNo(), 30), CurrentUser.name());
        audit.record("PAY_DETAILS_CHANGED", "StaffMember", staffId, Map.of("basic", basic));
        return payDetails(staffId);
    }

    @Transactional
    public PayDetails addItem(Long staffId, PayItemRequest r) {
        access.require("change pay details", PREPARE);
        exists(staffId);
        String kind = kind(r.kind());
        String name = required(r.name(), 80, "the name (e.g. Housing allowance, SACCOS)");
        if (r.amount() == null || r.amount().signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "Give the monthly amount");
        if (r.balance() != null && (r.balance().signum() < 0 || "ALLOWANCE".equals(kind))) {
            throw new BusinessRuleException("BALANCE_INVALID", "Only a deduction (a loan or an advance) has a balance");
        }
        Long id = jdbc.queryForObject("""
                insert into staff_pay_items (tenant_id, staff_id, kind, name, amount, taxable, balance, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, staffId, kind, name, r.amount(),
                !"ALLOWANCE".equals(kind) || !Boolean.FALSE.equals(r.taxable()), r.balance(), CurrentUser.name());
        audit.record("PAY_ITEM_ADDED", "StaffMember", staffId, Map.of("item", id, "name", name, "amount", r.amount()));
        return payDetails(staffId);
    }

    @Transactional
    public PayDetails saveItem(Long itemId, PayItemRequest r) {
        access.require("change pay details", PREPARE);
        Long staffId = jdbc.query("select staff_id from staff_pay_items where id = ?", (rs, n) -> rs.getLong(1), itemId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Item not found"));
        if (r.amount() == null || r.amount().signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "Give the monthly amount");
        if (r.balance() != null && r.balance().signum() < 0) throw new BusinessRuleException("BALANCE_INVALID", "The balance cannot be negative");
        jdbc.update("update staff_pay_items set name = coalesce(?, name), amount = ?, taxable = case when kind = 'ALLOWANCE' then ? else true end, "
                        + "balance = case when kind = 'DEDUCTION' then ? else null end, active = ? where id = ?",
                cut(r.name(), 80), r.amount(), !Boolean.FALSE.equals(r.taxable()), r.balance(), !Boolean.FALSE.equals(r.active()), itemId);
        audit.record("PAY_ITEM_CHANGED", "StaffMember", staffId, Map.of("item", itemId, "amount", r.amount()));
        return payDetails(staffId);
    }

    // ------------------------------------------------------------------ the monthly payroll

    @Transactional(readOnly = true)
    public List<RunView> runs() {
        return jdbc.query(RUN + " order by period desc limit 36", (rs, n) -> run(rs));
    }

    /** Prepares the month's payroll, or works it out again while it is a draft (after pay details changed). */
    @Transactional
    public RunDetail prepare(YearMonth month) {
        access.require("prepare the payroll", PREPARE);
        if (month == null) throw new BusinessRuleException("MONTH_REQUIRED", "Choose the month");
        if (month.isAfter(YearMonth.now(TZ).plusMonths(1))) throw new BusinessRuleException("MONTH_TOO_EARLY", "A payroll is prepared at most a month ahead");
        LocalDate period = month.atDay(1);
        Map<String, Object> existing = jdbc.queryForList("select id, status from payroll_runs where period = ? for update", period).stream().findFirst().orElse(null);
        Settings settings = settings();
        PayCalc.Rates rates = rates(settings);
        Long runId;
        if (existing == null) {
            runId = jdbc.queryForObject("""
                    insert into payroll_runs (tenant_id, period, rates, prepared_by, prepared_by_name) values (cims_current_tenant(), ?, ?::jsonb, ?, ?)
                    returning id""", Long.class, period, json(rates), CurrentUser.id().orElse(null), CurrentUser.name());
        } else {
            if (!"DRAFT".equals(existing.get("status"))) {
                throw new BusinessRuleException("PAYROLL_LOCKED", "The payroll for " + MONTH.format(month) + " is already approved");
            }
            runId = ((Number) existing.get("id")).longValue();
            jdbc.update("delete from payslips where run_id = ?", runId);
            jdbc.update("update payroll_runs set rates = ?::jsonb, prepared_by = ?, prepared_by_name = ?, prepared_at = now() where id = ?",
                    json(rates), CurrentUser.id().orElse(null), CurrentUser.name(), runId);
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                select m.id, m.staff_no, m.full_name, m.job_title, d.name as dept, p.basic_salary, p.bank_name, p.bank_branch, p.bank_account, p.tin,
                       p.pension_no, p.heslb_loan
                from staff_members m join staff_pay p on p.staff_id = m.id left join departments d on d.id = m.department_id
                where m.status = 'ACTIVE' and p.on_payroll and p.basic_salary > 0 order by m.full_name""");
        for (Map<String, Object> p : people) {
            Long sid = ((Number) p.get("id")).longValue();
            PayCalc.Result r = PayCalc.compute(rates, (BigDecimal) p.get("basic_salary"), Boolean.TRUE.equals(p.get("heslb_loan")),
                    calcItems(items(sid).stream().filter(PayItem::active).toList()));
            jdbc.update("""
                    insert into payslips (tenant_id, run_id, staff_id, staff_no, full_name, job_title, department, bank_name, bank_branch, bank_account, tin,
                      pension_no, basic, taxable_allowances, other_allowances, gross, pension_employee, taxable_income, paye, nhif_employee, heslb,
                      other_deductions, net, pension_employer, nhif_employer, wcf, sdl, lines)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                    runId, sid, p.get("staff_no"), p.get("full_name"), p.get("job_title"), p.get("dept"), p.get("bank_name"), p.get("bank_branch"),
                    p.get("bank_account"), p.get("tin"), p.get("pension_no"), r.basic(), r.taxableAllowances(), r.otherAllowances(), r.gross(),
                    r.pensionEmployee(), r.taxableIncome(), r.paye(), r.nhifEmployee(), r.heslb(), r.otherDeductions(), r.net(), r.pensionEmployer(),
                    r.nhifEmployer(), r.wcf(), r.sdl(), json(r.lines()));
        }
        jdbc.update("""
                update payroll_runs r set staff_count = x.n, gross_total = x.g, net_total = x.net, employer_total = x.e
                from (select count(*) n, coalesce(sum(gross), 0) g, coalesce(sum(net), 0) net,
                             coalesce(sum(pension_employer + nhif_employer + wcf + sdl), 0) e from payslips where run_id = ?) x where r.id = ?""", runId, runId);
        audit.record("PAYROLL_PREPARED", "PayrollRun", runId, Map.of("month", period.toString(), "staff", people.size()));
        return detail(runId);
    }

    @Transactional(readOnly = true)
    public RunDetail detail(Long runId) {
        RunView run = jdbc.query(RUN + " where id = ?", (rs, n) -> run(rs), runId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payroll not found"));
        List<Slip> slips = jdbc.query(SLIP + " where s.run_id = ? order by s.full_name", (rs, n) -> slip(rs), runId);
        List<String> problems = new ArrayList<>();
        for (Slip s : slips) {
            if (s.bankAccount() == null || s.bankName() == null) problems.add(s.fullName() + ": no bank account; the bank file leaves them out");
            if (s.warning() != null) problems.add(s.fullName() + ": " + s.warning());
        }
        int missing = jdbc.queryForObject("""
                select count(*) from staff_members m left join staff_pay p on p.staff_id = m.id
                where m.status = 'ACTIVE' and m.employment <> 'VOLUNTEER' and (p.staff_id is null or (p.on_payroll and p.basic_salary = 0))""", Integer.class);
        if (missing > 0) problems.add(missing + (missing == 1 ? " member" : " members") + " of staff have no basic salary and are not on this payroll");
        return new RunDetail(run, slips, totals(slips), problems);
    }

    @Transactional
    public RunDetail approve(Long runId) {
        access.require("approve the payroll", APPROVE);
        Map<String, Object> r = lock(runId);
        if (!"DRAFT".equals(r.get("status"))) throw new BusinessRuleException("PAYROLL_NOT_DRAFT", "This payroll is not waiting for approval");
        if (Objects.equals(r.get("prepared_by") == null ? null : ((Number) r.get("prepared_by")).longValue(), CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("PREPARED_BY_YOU", "You prepared this payroll; someone else approves it");
        }
        if (((Number) r.get("staff_count")).intValue() == 0) throw new BusinessRuleException("PAYROLL_EMPTY", "There is nobody on this payroll");
        jdbc.update("update payroll_runs set status = 'APPROVED', approved_by = ?, approved_by_name = ?, approved_at = now() where id = ?",
                CurrentUser.id().orElse(null), CurrentUser.name(), runId);
        audit.record("PAYROLL_APPROVED", "PayrollRun", runId, Map.of("net", r.get("net_total")));
        return detail(runId);
    }

    @Transactional
    public RunDetail returnToDraft(Long runId, String note) {
        access.require("return the payroll", APPROVE);
        Map<String, Object> r = lock(runId);
        if (!"APPROVED".equals(r.get("status"))) throw new BusinessRuleException("PAYROLL_NOT_APPROVED", "Only an approved payroll that is not yet paid can go back");
        if (note == null || note.isBlank()) throw new BusinessRuleException("REASON_REQUIRED", "Say what is to be corrected");
        jdbc.update("update payroll_runs set status = 'DRAFT', approved_by = null, approved_by_name = null, approved_at = null where id = ?", runId);
        audit.record("PAYROLL_RETURNED", "PayrollRun", runId, Map.of("note", cut(note, 300)));
        return detail(runId);
    }

    @Transactional
    public void delete(Long runId) {
        access.require("delete a draft payroll", PREPARE);
        Map<String, Object> r = lock(runId);
        if (!"DRAFT".equals(r.get("status"))) throw new BusinessRuleException("PAYROLL_LOCKED", "Only a draft payroll can be deleted");
        jdbc.update("delete from payroll_runs where id = ?", runId);
        audit.record("PAYROLL_DELETED", "PayrollRun", runId, Map.of("month", String.valueOf(r.get("period"))));
    }

    /** Records that salaries were paid: loan balances go down, and each person is told their payslip is ready. */
    @Transactional
    public RunDetail markPaid(Long runId, LocalDate paidOn) {
        access.require("record the payment of salaries", PAY);
        Map<String, Object> r = lock(runId);
        if (!"APPROVED".equals(r.get("status"))) throw new BusinessRuleException("PAYROLL_NOT_APPROVED", "The payroll must be approved before it is paid");
        LocalDate on = paidOn == null ? LocalDate.now(TZ) : paidOn;
        if (on.isAfter(LocalDate.now(TZ))) throw new BusinessRuleException("DATE_IN_FUTURE", "The payment date cannot be in the future");
        jdbc.update("update payroll_runs set status = 'PAID', paid_by_name = ?, paid_at = now(), paid_on = ? where id = ?", CurrentUser.name(), on, runId);
        String month = MONTH.format(((java.sql.Date) r.get("period")).toLocalDate());
        RunDetail d = detail(runId);
        for (Slip s : d.slips()) {
            for (PayCalc.Line l : s.lines()) {
                if (!"DEDUCTION".equals(l.kind()) || l.itemId() == null) continue;
                jdbc.update("update staff_pay_items set balance = greatest(balance - ?, 0), active = case when balance - ? <= 0 then false else active end "
                        + "where id = ? and balance is not null", l.amount(), l.amount(), l.itemId());
            }
            String phone = jdbc.queryForList("select phone from staff_members where id = ? and phone is not null", String.class, s.staffId()).stream()
                    .findFirst().orElse(null);
            messaging.event("PAYSLIP_READY", phone, Map.of("month", month));
        }
        audit.record("PAYROLL_PAID", "PayrollRun", runId, Map.of("paidOn", on.toString(), "net", r.get("net_total")));
        return d;
    }

    // ------------------------------------------------------------------ files

    /** The payroll register with the schedules for TRA (PAYE, SDL), the pension fund, NHIF, HESLB and WCF. */
    @Transactional
    public byte[] workbook(Long runId) {
        RunDetail d = detail(runId);
        Settings set = settings();
        List<List<Object>> reg = new ArrayList<>(), paye = new ArrayList<>(), pension = new ArrayList<>(), nhif = new ArrayList<>(), heslb = new ArrayList<>();
        List<String> deductionNames = d.slips().stream().flatMap(s -> s.lines().stream()).filter(l -> "DEDUCTION".equals(l.kind()))
                .map(PayCalc.Line::name).distinct().sorted().toList();
        List<List<Object>> others = new ArrayList<>();
        Map<Long, Map<String, Object>> pay = new LinkedHashMap<>();
        jdbc.queryForList("select staff_id, nhif_no, heslb_index_no from staff_pay").forEach(m -> pay.put(((Number) m.get("staff_id")).longValue(), m));
        for (Slip s : d.slips()) {
            reg.add(Arrays.asList(s.staffNo(), s.fullName(), s.department(), s.basic(), s.taxableAllowances().add(s.otherAllowances()), s.gross(),
                    s.pensionEmployee(), s.paye(), s.nhifEmployee(), s.heslb(), s.otherDeductions(), s.totalDeductions(), s.net(), s.pensionEmployer(),
                    s.nhifEmployer(), s.wcf(), s.sdl()));
            paye.add(Arrays.asList(s.staffNo(), s.fullName(), s.tin(), s.basic(), s.taxableAllowances(), s.otherAllowances(), s.gross(), s.pensionEmployee(),
                    s.taxableIncome(), s.paye()));
            pension.add(Arrays.asList(s.staffNo(), s.fullName(), s.pensionNo(), set.pensionOnBasic() ? s.basic() : s.gross(), s.pensionEmployee(),
                    s.pensionEmployer(), s.pensionEmployee().add(s.pensionEmployer())));
            Map<String, Object> p = pay.getOrDefault(s.staffId(), Map.of());
            if (s.nhifEmployee().signum() > 0 || s.nhifEmployer().signum() > 0) {
                nhif.add(Arrays.asList(s.staffNo(), s.fullName(), p.get("nhif_no"), s.basic(), s.nhifEmployee(), s.nhifEmployer(),
                        s.nhifEmployee().add(s.nhifEmployer())));
            }
            if (s.heslb().signum() > 0) heslb.add(Arrays.asList(s.staffNo(), s.fullName(), p.get("heslb_index_no"), s.basic(), s.heslb()));
            for (PayCalc.Line l : s.lines()) {
                if ("DEDUCTION".equals(l.kind())) others.add(Arrays.asList(l.name(), s.staffNo(), s.fullName(), l.amount()));
            }
        }
        Totals t = d.totals();
        reg.add(Arrays.asList(null, "Total", null, t.basic(), t.gross().subtract(t.basic()), t.gross(), t.pensionEmployee(), t.paye(), t.nhifEmployee(),
                t.heslb(), t.otherDeductions(), t.pensionEmployee().add(t.paye()).add(t.nhifEmployee()).add(t.heslb()).add(t.otherDeductions()), t.net(),
                t.pensionEmployer(), t.nhifEmployer(), t.wcf(), t.sdl()));
        others.sort((a, b) -> String.valueOf(a.get(0)).compareTo(String.valueOf(b.get(0))));
        List<List<Object>> employer = List.of(
                Arrays.asList("Gross pay", t.gross(), null),
                Arrays.asList("PAYE (to TRA)", t.paye(), null),
                Arrays.asList("Skills and Development Levy (to TRA)", t.sdl(), set.sdlApplies() ? set.sdlRate() + "% of gross" : "Not charged (exempt)"),
                Arrays.asList(set.pensionFund() + " (employee + employer)", t.pensionEmployee().add(t.pensionEmployer()),
                        set.pensionEmployeeRate() + "% + " + set.pensionEmployerRate() + "% of " + (set.pensionOnBasic() ? "basic" : "gross")),
                Arrays.asList("NHIF (employee + employer)", t.nhifEmployee().add(t.nhifEmployer()), set.nhifApplies() ? null : "Not charged"),
                Arrays.asList("WCF", t.wcf(), set.wcfRate() + "% of gross"),
                Arrays.asList("HESLB loan repayments", t.heslb(), set.heslbRate() + "% of basic"),
                Arrays.asList("Net pay to banks", t.net(), null),
                Arrays.asList("Other deductions (" + String.join(", ", deductionNames) + ")", t.otherDeductions(), null));
        audit.record("PAYROLL_DOWNLOADED", "PayrollRun", runId, Map.of("staff", d.slips().size()));
        return new SimpleXlsx()
                .sheet("Payroll", List.of("Staff no", "Name", "Department", "Basic", "Allowances", "Gross", set.pensionFund() + " (employee)", "PAYE", "NHIF",
                        "HESLB", "Other deductions", "Total deductions", "Net pay", set.pensionFund() + " (employer)", "NHIF (employer)", "WCF", "SDL"),
                        reg, 12, 28, 20, 12, 12, 12, 12, 12, 10, 10, 12, 14, 12, 12, 12, 10, 10)
                .sheet("Summary", List.of("Payment " + d.run().month(), "Amount (TZS)", "Basis"), employer, 44, 16, 30)
                .sheet("PAYE", List.of("Staff no", "Name", "TIN", "Basic", "Taxable allowances", "Other allowances", "Gross", "Pension deducted",
                        "Taxable income", "PAYE"), paye, 12, 28, 14, 12, 14, 14, 12, 14, 14, 12)
                .sheet(set.pensionFund(), List.of("Staff no", "Name", "Member no", "Wages", "Employee", "Employer", "Total"), pension, 12, 28, 16, 12, 12, 12, 12)
                .sheet("NHIF", List.of("Staff no", "Name", "NHIF no", "Basic", "Employee", "Employer", "Total"), nhif, 12, 28, 16, 12, 12, 12, 12)
                .sheet("HESLB", List.of("Staff no", "Name", "Form four index no", "Basic", "Repayment"), heslb, 12, 28, 20, 12, 12)
                .sheet("Other deductions", List.of("Deduction", "Staff no", "Name", "Amount"), others, 24, 12, 28, 12)
                .bytes();
    }

    /** The bank file: one line per person with a bank account (CSV that banks' bulk payment services take). */
    @Transactional
    public byte[] bankFile(Long runId) {
        access.require("download the bank file", PAY);
        RunDetail d = detail(runId);
        if ("DRAFT".equals(d.run().status())) throw new BusinessRuleException("PAYROLL_NOT_APPROVED", "The bank file is made from an approved payroll");
        StringBuilder sb = new StringBuilder("Staff no,Name,Bank,Branch,Account,Amount,Reference\r\n");
        String ref = "Salary " + d.run().month();
        for (Slip s : d.slips()) {
            if (s.bankAccount() == null || s.bankName() == null || s.net().signum() <= 0) continue;
            sb.append(csv(s.staffNo())).append(',').append(csv(s.fullName())).append(',').append(csv(s.bankName())).append(',').append(csv(s.bankBranch()))
                    .append(',').append(csv(s.bankAccount().replace(" ", ""))).append(',').append(s.net().toPlainString()).append(',').append(csv(ref)).append("\r\n");
        }
        audit.record("PAYROLL_BANK_FILE", "PayrollRun", runId, Map.of("month", d.run().month()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A payslip as PDF: for the person themselves (once paid) or for payroll staff. */
    @Transactional
    public byte[] payslipPdf(Long slipId) {
        Slip s = jdbc.query(SLIP + " where s.id = ?", (rs, n) -> slip(rs), slipId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payslip not found"));
        boolean payroll = access.has(PREPARE) || access.has(APPROVE) || access.has(PAY) || access.has(Role.AUDITOR);
        if (!payroll) {
            Long mine = staff.myStaffId();
            String status = jdbc.queryForObject("select status from payroll_runs where id = ?", String.class, s.runId());
            if (!s.staffId().equals(mine) || !"PAID".equals(status)) throw new AccessDeniedException("Not your payslip");
        }
        audit.record("PAYSLIP_DOWNLOADED", "Payslip", slipId, Map.of("staff", s.staffNo()));
        return PdfRenderer.render(payslipHtml(s), "payslip");
    }

    @Transactional(readOnly = true)
    public List<MySlip> mine() {
        Long me = staff.myStaffId();
        return jdbc.query("""
                select s.id, r.period, s.gross, s.pension_employee + s.paye + s.nhif_employee + s.heslb + s.other_deductions, s.net, r.paid_on
                from payslips s join payroll_runs r on r.id = s.run_id where s.staff_id = ? and r.status = 'PAID' order by r.period desc limit 36""",
                (rs, n) -> {
                    LocalDate p = rs.getObject(2, LocalDate.class);
                    return new MySlip(rs.getLong(1), MONTH.format(p), p, rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class));
                }, me);
    }

    @Transactional(readOnly = true)
    public long waitingForApproval() {
        Long n = jdbc.queryForObject("select count(*) from payroll_runs where status = 'DRAFT' and staff_count > 0 and (prepared_by is null or prepared_by <> ?)",
                Long.class, CurrentUser.id().orElse(-1L));
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ helpers

    private static final String RUN = """
            select id, period, status, staff_count, gross_total, net_total, employer_total, prepared_by, prepared_by_name, prepared_at, approved_by_name,
                   approved_at, paid_by_name, paid_at, paid_on from payroll_runs""";

    private RunView run(ResultSet rs) throws SQLException {
        LocalDate period = rs.getObject("period", LocalDate.class);
        String status = rs.getString("status");
        Long by = rs.getObject("prepared_by") == null ? null : rs.getLong("prepared_by");
        boolean own = Objects.equals(by, CurrentUser.id().orElse(null));
        boolean approver = access.has(APPROVE);
        String note = "DRAFT".equals(status) && own && approver ? "You prepared this payroll; someone else approves it." : null;
        return new RunView(rs.getLong("id"), period, MONTH.format(period), status, rs.getInt("staff_count"), rs.getBigDecimal("gross_total"),
                rs.getBigDecimal("net_total"), rs.getBigDecimal("employer_total"), rs.getString("prepared_by_name"), odt(rs.getTimestamp("prepared_at")),
                rs.getString("approved_by_name"), odt(rs.getTimestamp("approved_at")), rs.getString("paid_by_name"), odt(rs.getTimestamp("paid_at")),
                rs.getObject("paid_on", LocalDate.class), "DRAFT".equals(status) && approver && !own && rs.getInt("staff_count") > 0, note);
    }

    private static final String SLIP = """
            select s.*, r.period from payslips s join payroll_runs r on r.id = s.run_id""";

    private Slip slip(ResultSet rs) throws SQLException {
        List<PayCalc.Line> lines = mapper.readValue(rs.getString("lines"), new TypeReference<List<PayCalc.Line>>() {
        });
        BigDecimal gross = rs.getBigDecimal("gross"), net = rs.getBigDecimal("net");
        BigDecimal ded = rs.getBigDecimal("pension_employee").add(rs.getBigDecimal("paye")).add(rs.getBigDecimal("nhif_employee"))
                .add(rs.getBigDecimal("heslb")).add(rs.getBigDecimal("other_deductions"));
        String warning = net.signum() < 0 ? "deductions are more than the pay"
                : gross.signum() > 0 && net.multiply(BigDecimal.valueOf(3)).compareTo(gross) < 0 ? "takes home less than a third of gross pay; check the deductions" : null;
        return new Slip(rs.getLong("id"), rs.getLong("run_id"), MONTH.format(rs.getObject("period", LocalDate.class)), rs.getLong("staff_id"),
                rs.getString("staff_no"), rs.getString("full_name"), rs.getString("job_title"), rs.getString("department"), rs.getString("bank_name"),
                rs.getString("bank_branch"), rs.getString("bank_account"), rs.getString("tin"), rs.getString("pension_no"), rs.getBigDecimal("basic"),
                rs.getBigDecimal("taxable_allowances"), rs.getBigDecimal("other_allowances"), gross, rs.getBigDecimal("pension_employee"),
                rs.getBigDecimal("taxable_income"), rs.getBigDecimal("paye"), rs.getBigDecimal("nhif_employee"), rs.getBigDecimal("heslb"),
                rs.getBigDecimal("other_deductions"), ded, net, rs.getBigDecimal("pension_employer"), rs.getBigDecimal("nhif_employer"),
                rs.getBigDecimal("wcf"), rs.getBigDecimal("sdl"), lines, warning);
    }

    private static Totals totals(List<Slip> slips) {
        BigDecimal[] t = new BigDecimal[12];
        Arrays.fill(t, BigDecimal.ZERO);
        for (Slip s : slips) {
            BigDecimal[] v = {s.basic(), s.gross(), s.pensionEmployee(), s.pensionEmployer(), s.paye(), s.nhifEmployee(), s.nhifEmployer(), s.heslb(),
                    s.otherDeductions(), s.net(), s.wcf(), s.sdl()};
            for (int i = 0; i < v.length; i++) t[i] = t[i].add(v[i]);
        }
        return new Totals(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11]);
    }

    private String payslipHtml(Slip s) {
        String inst = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        Settings set = settings();
        StringBuilder earn = new StringBuilder(row("Basic salary", s.basic()));
        StringBuilder ded = new StringBuilder();
        for (PayCalc.Line l : s.lines()) if ("ALLOWANCE".equals(l.kind())) earn.append(row(l.name(), l.amount()));
        ded.append(row(set.pensionFund() + " (employee)", s.pensionEmployee())).append(row("PAYE", s.paye()));
        if (s.nhifEmployee().signum() > 0) ded.append(row("NHIF", s.nhifEmployee()));
        if (s.heslb().signum() > 0) ded.append(row("HESLB loan repayment", s.heslb()));
        for (PayCalc.Line l : s.lines()) if ("DEDUCTION".equals(l.kind())) ded.append(row(l.name(), l.amount()));
        return "<html><head><style>body{font-family:sans-serif;font-size:10pt;color:#0F1B2D}h1{font-size:14pt;margin:0}h2{font-size:11pt;margin:14pt 0 4pt}"
                + "table{width:100%;border-collapse:collapse}td{padding:3pt 4pt;border-bottom:0.5pt solid #ccd}td.a{text-align:right}"
                + ".tot td{font-weight:bold;border-top:1pt solid #0F1B2D}.muted{color:#4A5568;font-size:9pt}.net{font-size:13pt;font-weight:bold;margin-top:10pt}"
                + "</style></head><body>"
                + "<h1>" + e(inst) + "</h1><div class=\"muted\">Payslip for " + e(s.month()) + "</div>"
                + "<table style=\"margin-top:10pt\"><tr><td>Name</td><td>" + e(s.fullName()) + "</td><td>Staff no</td><td>" + e(s.staffNo()) + "</td></tr>"
                + "<tr><td>Job title</td><td>" + e(s.jobTitle()) + "</td><td>Department</td><td>" + e(s.department()) + "</td></tr>"
                + "<tr><td>TIN</td><td>" + e(s.tin()) + "</td><td>" + e(set.pensionFund()) + " no</td><td>" + e(s.pensionNo()) + "</td></tr>"
                + "<tr><td>Bank</td><td>" + e(s.bankName()) + "</td><td>Account</td><td>" + e(mask(s.bankAccount())) + "</td></tr></table>"
                + "<h2>Earnings</h2><table>" + earn + "<tr class=\"tot\"><td>Gross pay</td><td class=\"a\">" + money(s.gross()) + "</td></tr></table>"
                + "<h2>Deductions</h2><table>" + ded + "<tr class=\"tot\"><td>Total deductions</td><td class=\"a\">" + money(s.totalDeductions()) + "</td></tr></table>"
                + "<div class=\"net\">Net pay: TZS " + money(s.net()) + "</div>"
                + "<p class=\"muted\">Taxable income TZS " + money(s.taxableIncome()) + ". The employer also pays " + e(set.pensionFund()) + " TZS "
                + money(s.pensionEmployer()) + (s.nhifEmployer().signum() > 0 ? ", NHIF TZS " + money(s.nhifEmployer()) : "") + " and WCF TZS "
                + money(s.wcf()) + " for you.</p>"
                + "<p class=\"muted\">Produced by CIMS. Questions about this payslip go to Human Resources or the Accounts office.</p></body></html>";
    }

    private static String row(String label, BigDecimal amount) {
        return "<tr><td>" + e(label) + "</td><td class=\"a\">" + money(amount) + "</td></tr>";
    }

    private static String mask(String account) {
        if (account == null) return null;
        String a = account.replace(" ", "");
        return a.length() <= 4 ? a : "•••• " + a.substring(a.length() - 4);
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.0f", v);
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }

    private static String csv(String s) {
        if (s == null) return "";
        String t = s.replace("\"", "\"\"");
        if (t.startsWith("=") || t.startsWith("+") || t.startsWith("-") || t.startsWith("@")) t = "'" + t;
        return "\"" + t + "\"";
    }

    private Map<String, Object> lock(Long runId) {
        return jdbc.queryForList("select * from payroll_runs where id = ? for update", runId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payroll not found"));
    }

    private List<PayItem> items(Long staffId) {
        return jdbc.query("select id, kind, name, amount, taxable, balance, active from staff_pay_items where staff_id = ? order by kind, name",
                (rs, n) -> new PayItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBoolean(5), rs.getBigDecimal(6),
                        rs.getBoolean(7)), staffId);
    }

    private static List<PayCalc.Item> calcItems(List<PayItem> items) {
        return items.stream().filter(PayItem::active).map(i -> new PayCalc.Item(i.id(), i.kind(), i.name(), i.amount(), i.taxable(), i.balance())).toList();
    }

    static PayCalc.Rates rates(Settings s) {
        return new PayCalc.Rates(s.pensionFund(), s.pensionEmployeeRate(), s.pensionEmployerRate(), s.pensionOnBasic(), s.payeBands(), s.wcfRate(),
                s.sdlApplies(), s.sdlRate(), s.nhifApplies(), s.nhifEmployeeRate(), s.nhifEmployerRate(), s.heslbRate());
    }

    private List<PayCalc.Band> bands(String text) {
        return mapper.readValue(text, new TypeReference<List<PayCalc.Band>>() {
        });
    }

    private String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    private void exists(Long staffId) {
        Integer n = jdbc.queryForObject("select count(*) from staff_members where id = ?", Integer.class, staffId);
        if (n == null || n == 0) throw new NotFoundException("Staff record not found");
    }

    private static String kind(String k) {
        String v = k == null ? "" : k.toUpperCase(Locale.ROOT);
        if (!v.equals("ALLOWANCE") && !v.equals("DEDUCTION")) throw new BusinessRuleException("KIND_INVALID", "An allowance or a deduction");
        return v;
    }

    private static String required(String s, int max, String what) {
        String t = cut(s, max);
        if (t == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return t;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
