package tz.co.msingi.cims.console.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.console.billing.BillingModels.*;
import tz.co.msingi.cims.finance.collection.PaymentProvider;
import tz.co.msingi.cims.notification.email.EmailMessage;
import tz.co.msingi.cims.notification.email.EmailService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.platform.tenancy.TenantWork;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Msingi billing (FR-ADM-S08): what Msingi Technologies charges each institution for CIMS. Charges come from the institution's
 * contract — a one-off set-up fee and the annual licence fee, invoiced in advance at the start of each contract year, and the fees
 * per active student (per year, in proportion to the cycle) and per paid application or a share of the application fees collected,
 * invoiced at the end of each usage cycle. Invoices are prepared as drafts (or issued at once when the contract says so), numbered
 * when issued, and paid by bank transfer or mobile money: Msingi staff record payments, or Msingi's payment collector reports them
 * with the invoice's payment reference. Payments are applied to the invoice quoted and then to the oldest; what is left is held as
 * credit for the next invoice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MsingiBillingService {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    static final Set<String> CYCLES = Set.of("MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");
    static final Set<String> METHODS = Set.of("BANK", "MOBILE_MONEY", "CHEQUE", "CASH", "OTHER");
    static final Set<String> LINE_KINDS = Set.of("SETUP", "LICENCE", "STUDENTS", "APPLICATIONS", "APPLICATION_SHARE", "OTHER", "DISCOUNT");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TenantRepository tenants;
    private final TenantWork tenantWork;
    private final PlatformAuditService platformAudit;
    private final EmailService email;
    private final BillingDocuments documents;

    @Value("${cims.payments.allow-simulation:false}")
    private boolean allowSimulation;

    static LocalDate today() {
        return LocalDate.now(TZ);
    }

    // ================================================================= settings

    @Transactional(readOnly = true)
    public Settings settings() {
        return jdbc.queryForObject("""
                select company_name, tin, vrn, address, phone, email, website, channels::text, invoice_note, updated_by_name, updated_at, logo is not null
                from platform_billing_settings where id = 1""", (rs, i) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), channels(rs.getString(8)), rs.getString(9), rs.getString(10),
                rs.getObject(11, OffsetDateTime.class), rs.getBoolean(12)));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        String name = trim(s.companyName());
        if (name == null) throw new BusinessRuleException("NAME_REQUIRED", "Enter the company name printed on invoices.");
        List<Channel> chans = s.channels() == null ? List.of() : s.channels().stream()
                .filter(c -> c != null && trim(c.accountNumber()) != null)
                .map(c -> new Channel(trim(c.provider()), trim(c.accountName()), trim(c.accountNumber()), trim(c.branch()))).toList();
        for (Channel c : chans) {
            if (c.provider() == null) throw new BusinessRuleException("PROVIDER_REQUIRED", "Choose the bank or mobile money service of account " + c.accountNumber() + ".");
        }
        jdbc.update("""
                update platform_billing_settings set company_name = ?, tin = ?, vrn = ?, address = ?, phone = ?, email = ?, website = ?,
                  channels = cast(? as jsonb), invoice_note = ?, updated_by_name = ?, updated_at = now() where id = 1""",
                name, trim(s.tin()), trim(s.vrn()), trim(s.address()), trim(s.phone()), trim(s.email()), trim(s.website()), write(chans),
                trim(s.invoiceNote()), CurrentUser.platformName());
        platformAudit.record("BILLING_SETTINGS_CHANGED", null, "BillingSettings", 1L, Map.of("channels", chans.size()));
        return settings();
    }

    /** Msingi's logo on its documents: the one uploaded under Invoice details, or the built-in one. */
    public record Logo(byte[] bytes, String contentType) {
    }

    private static final byte[] BUILT_IN_LOGO;

    static {
        try (var in = MsingiBillingService.class.getResourceAsStream("/branding/msingi-logo.png")) {
            BUILT_IN_LOGO = in == null ? new byte[0] : in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public Logo logo() {
        return jdbc.query("select logo, logo_type from platform_billing_settings where id = 1 and logo is not null",
                (rs, i) -> new Logo(rs.getBytes(1), rs.getString(2))).stream().findFirst().orElse(new Logo(BUILT_IN_LOGO, "image/png"));
    }

    /** Replaces Msingi's logo (PNG or JPEG up to 1 MB, at least 120 pixels wide); null bytes go back to the built-in logo. */
    @Transactional
    public Settings saveLogo(byte[] bytes) {
        if (bytes == null) {
            jdbc.update("update platform_billing_settings set logo = null, logo_type = null, updated_by_name = ?, updated_at = now() where id = 1", CurrentUser.platformName());
        } else {
            if (bytes.length > 1024 * 1024) throw new BusinessRuleException("LOGO_TOO_LARGE", "Use a logo of at most 1 MB.");
            String type = bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' ? "image/png"
                    : bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 ? "image/jpeg" : null;
            if (type == null) throw new BusinessRuleException("LOGO_TYPE", "The logo must be a PNG or JPEG image.");
            try {
                var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (img == null || img.getWidth() < 120) throw new BusinessRuleException("LOGO_TOO_SMALL", "Use a logo at least 120 pixels wide so it prints sharply.");
            } catch (java.io.IOException e) {
                throw new BusinessRuleException("LOGO_TYPE", "The logo could not be read.");
            }
            jdbc.update("update platform_billing_settings set logo = ?, logo_type = ?, updated_by_name = ?, updated_at = now() where id = 1", bytes, type, CurrentUser.platformName());
        }
        platformAudit.record("BILLING_LOGO_CHANGED", null, "BillingSettings", 1L, Map.of("custom", bytes != null));
        return settings();
    }

    // ================================================================= contracts

    @Transactional(readOnly = true)
    public List<Contract> contracts(Long tenantId) {
        tenant(tenantId);
        return jdbc.query(CONTRACT + " where tenant_id = ? order by status, start_date desc, id desc", CONTRACT_ROW, tenantId);
    }

    /** Creates the institution's contract, or changes the one in force. Dates already invoiced cannot move. */
    @Transactional
    public Contract saveContract(Long tenantId, ContractInput in) {
        Tenant t = tenant(tenantId);
        if (in.startDate() == null) throw new BusinessRuleException("START_REQUIRED", "Enter the date the contract starts.");
        if (in.endDate() != null && in.endDate().isBefore(in.startDate())) throw new BusinessRuleException("END_BEFORE_START", "The contract cannot end before it starts.");
        String cycle = in.usageCycle() == null ? "QUARTERLY" : in.usageCycle();
        if (!CYCLES.contains(cycle)) throw new BusinessRuleException("INVALID_CYCLE", "Choose how often usage is invoiced.");
        BigDecimal pct = money(in.applicationPercent());
        if (pct.compareTo(HUNDRED) > 0) throw new BusinessRuleException("INVALID_PERCENT", "The share of application fees must be between 0 and 100%.");
        BigDecimal vat = in.vatPercent() == null ? BigDecimal.valueOf(18) : in.vatPercent();
        if (vat.signum() < 0 || vat.compareTo(HUNDRED) > 0) throw new BusinessRuleException("INVALID_VAT", "VAT must be between 0 and 100%.");
        int terms = in.paymentTermsDays() == null ? 30 : in.paymentTermsDays();
        if (terms < 0 || terms > 365) throw new BusinessRuleException("INVALID_TERMS", "Payment terms must be between 0 and 365 days.");
        int suspendAfter = in.suspendAfterDays() == null ? 60 : in.suspendAfterDays();
        if (suspendAfter < 7 || suspendAfter > 365) throw new BusinessRuleException("INVALID_SUSPEND_AFTER", "Suspension can follow 7 to 365 days after the due date.");
        for (BigDecimal b : new BigDecimal[]{in.setupFee(), in.annualFee(), in.studentFee(), in.applicationFee()}) {
            if (b != null && b.signum() < 0) throw new BusinessRuleException("NEGATIVE_FEE", "Fees cannot be negative.");
        }
        Contract current = active(tenantId);
        Object[] values = {trim(in.reference()), Date.valueOf(in.startDate()), in.endDate() == null ? null : Date.valueOf(in.endDate()),
                money(in.setupFee()), money(in.annualFee()), money(in.studentFee()), money(in.applicationFee()), pct, cycle, vat, terms,
                Boolean.TRUE.equals(in.autoIssue()), Boolean.TRUE.equals(in.autoSuspend()), suspendAfter, trim(in.billingContactName()),
                trim(in.billingEmail()), trim(in.billingPhone()), trim(in.notes())};
        Long id;
        if (current == null) {
            id = jdbc.queryForObject("""
                    insert into billing_contracts (reference, start_date, end_date, setup_fee, annual_fee, student_fee, application_fee,
                      application_percent, usage_cycle, vat_percent, payment_terms_days, auto_issue, auto_suspend, suspend_after_days,
                      billing_contact_name, billing_email, billing_phone, notes, tenant_id, created_by_name)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                    append(values, tenantId, CurrentUser.platformName()));
            platformAudit.record("BILLING_CONTRACT_CREATED", tenantId, "BillingContract", id, Map.of("institution", t.getCode(), "starts", in.startDate().toString()));
        } else {
            boolean billed = current.licenceBilledUntil() != null || current.usageBilledUntil() != null;
            if (billed && !current.startDate().equals(in.startDate())) {
                throw new BusinessRuleException("START_LOCKED", "Invoices have been prepared from this contract, so its start date cannot change. End it and start a new one.");
            }
            if (billed && !current.usageCycle().equals(cycle)) {
                throw new BusinessRuleException("CYCLE_LOCKED", "Usage has been invoiced on this contract, so the cycle cannot change. End it and start a new one.");
            }
            id = current.id();
            jdbc.update("""
                    update billing_contracts set reference = ?, start_date = ?, end_date = ?, setup_fee = ?, annual_fee = ?, student_fee = ?,
                      application_fee = ?, application_percent = ?, usage_cycle = ?, vat_percent = ?, payment_terms_days = ?, auto_issue = ?,
                      auto_suspend = ?, suspend_after_days = ?, billing_contact_name = ?, billing_email = ?, billing_phone = ?, notes = ?,
                      updated_at = now(), version = version + 1 where id = ?""", append(values, id));
            platformAudit.record("BILLING_CONTRACT_CHANGED", tenantId, "BillingContract", id, Map.of("institution", t.getCode()));
        }
        return contract(id);
    }

    @Transactional
    public Contract endContract(Long tenantId, LocalDate endDate) {
        Contract c = active(tenantId);
        if (c == null) throw new BusinessRuleException("NO_CONTRACT", "This institution has no contract in force.");
        LocalDate end = endDate == null ? today() : endDate;
        if (end.isBefore(c.startDate())) throw new BusinessRuleException("END_BEFORE_START", "The contract cannot end before it starts.");
        jdbc.update("update billing_contracts set status = 'ENDED', end_date = ?, updated_at = now(), version = version + 1 where id = ?", Date.valueOf(end), c.id());
        platformAudit.record("BILLING_CONTRACT_ENDED", tenantId, "BillingContract", c.id(), Map.of("ends", end.toString()));
        return contract(c.id());
    }

    Contract active(Long tenantId) {
        return jdbc.query(CONTRACT + " where tenant_id = ? and status = 'ACTIVE'", CONTRACT_ROW, tenantId).stream().findFirst().orElse(null);
    }

    private Contract contract(Long id) {
        return jdbc.queryForObject(CONTRACT + " where id = ?", CONTRACT_ROW, id);
    }

    // ================================================================= usage

    /**
     * What the institution used in a period. Active students: those active now or with a semester registration (not rejected or
     * cancelled) in a semester overlapping the period. Applications: application-fee invoices first paid in the period, and the
     * application fees received in it.
     */
    public Usage usage(Long tenantId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw new BusinessRuleException("INVALID_PERIOD", "Choose a period that ends after it starts.");
        Tenant t = tenant(tenantId);
        Timestamp start = Timestamp.from(from.atStartOfDay(TZ).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(TZ).toInstant());
        return tenantWork.read(t, () -> {
            Long students = jdbc.queryForObject("""
                    select count(*) from students s where s.status = 'ACTIVE' or exists (
                      select 1 from semester_registrations r join semesters m on m.id = r.semester_id
                      where r.student_id = s.id and r.status in ('SUBMITTED', 'APPROVED') and m.start_date <= ? and m.end_date >= ?)""",
                    Long.class, Date.valueOf(to), Date.valueOf(from));
            Map<String, Object> apps = jdbc.queryForMap("""
                    select count(*) filter (where first_paid >= ? and first_paid < ?) as paid_apps, coalesce(sum(received), 0) as received from (
                      select i.id, min(p.paid_at) as first_paid,
                             sum(p.amount) filter (where p.paid_at >= ? and p.paid_at < ?) as received
                      from invoices i join payments p on p.invoice_id = i.id and p.status <> 'REVERSED'
                      where i.purpose = 'APPLICATION_FEE' group by i.id) x""", start, end, start, end);
            return new Usage(from, to, students == null ? 0 : students, ((Number) apps.get("paid_apps")).longValue(),
                    money((BigDecimal) apps.get("received")));
        });
    }

    // ================================================================= preparing invoices

    /** Prepares a draft invoice for the institution: the licence year, a usage period, or an empty one for other charges. */
    @Transactional
    public InvoiceView prepare(Long tenantId, String kind, LocalDate from, LocalDate to) {
        Contract c = active(tenantId);
        Long id = switch (kind == null ? "" : kind) {
            case "LICENCE" -> {
                if (c == null) throw new BusinessRuleException("NO_CONTRACT", "Record the institution's contract first.");
                LocalDate f = from == null ? nextLicenceYear(c) : from;
                yield licenceInvoice(c, f, to == null ? yearEnd(c, f) : to, false);
            }
            case "USAGE" -> {
                if (c == null) throw new BusinessRuleException("NO_CONTRACT", "Record the institution's contract first.");
                if (from == null || to == null) throw new BusinessRuleException("INVALID_PERIOD", "Choose the usage period.");
                yield usageInvoice(c, from, to, false);
            }
            case "OTHER" -> newInvoice(tenantId, c, "OTHER", from, to, c == null ? BigDecimal.valueOf(18) : c.vatPercent(), false);
            default -> throw new BusinessRuleException("INVALID_KIND", "Choose what the invoice is for.");
        };
        platformAudit.record("BILLING_INVOICE_PREPARED", tenantId, "BillingInvoice", id, Map.of("kind", kind));
        return view(id, true);
    }

    LocalDate nextLicenceYear(Contract c) {
        return c.licenceBilledUntil() == null ? c.startDate() : c.licenceBilledUntil().plusDays(1);
    }

    LocalDate yearEnd(Contract c, LocalDate from) {
        LocalDate end = from.plusYears(1).minusDays(1);
        return c.endDate() != null && c.endDate().isBefore(end) ? c.endDate() : end;
    }

    /** The set-up fee (once) and the licence for the contract year; the licence is in proportion when the year is cut short. */
    Long licenceInvoice(Contract c, LocalDate from, LocalDate to, boolean generated) {
        if (to.isBefore(from)) throw new BusinessRuleException("INVALID_PERIOD", "Choose a period that ends after it starts.");
        List<Line> lines = new ArrayList<>();
        if (!c.setupBilled() && c.setupFee().signum() > 0) {
            lines.add(new Line("SETUP", "One-off set-up: installation, data migration and training", BigDecimal.ONE, c.setupFee(), c.setupFee()));
        }
        if (c.annualFee().signum() > 0) {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            long yearDays = ChronoUnit.DAYS.between(from, from.plusYears(1));
            BigDecimal amount = days >= yearDays ? c.annualFee() : whole(c.annualFee().multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(yearDays), 6, RoundingMode.HALF_UP));
            lines.add(new Line("LICENCE", "CIMS annual licence, " + DAY.format(from) + " – " + DAY.format(to)
                    + (days >= yearDays ? "" : " (" + days + " of " + yearDays + " days)"), BigDecimal.ONE, amount, amount));
        }
        Long id = newInvoice(c.tenantId(), c, "LICENCE", from, to, c.vatPercent(), generated);
        writeLines(id, c.tenantId(), lines);
        jdbc.update("update billing_contracts set setup_billed = true, licence_billed_until = greatest(coalesce(licence_billed_until, ?), ?), updated_at = now() where id = ?",
                Date.valueOf(to), Date.valueOf(to), c.id());
        return id;
    }

    /** Fees for what the institution used in the period: active students (per year, in proportion), paid applications, the share of fees. */
    Long usageInvoice(Contract c, LocalDate from, LocalDate to, boolean generated) {
        Usage u = usage(c.tenantId(), from, to);
        List<Line> lines = usageLines(c, u);
        Long id = newInvoice(c.tenantId(), c, "USAGE", from, to, c.vatPercent(), generated);
        writeLines(id, c.tenantId(), lines);
        jdbc.update("update billing_contracts set usage_billed_until = greatest(coalesce(usage_billed_until, ?), ?), updated_at = now() where id = ?",
                Date.valueOf(to), Date.valueOf(to), c.id());
        return id;
    }

    static List<Line> usageLines(Contract c, Usage u) {
        List<Line> lines = new ArrayList<>();
        String period = DAY.format(u.from()) + " – " + DAY.format(u.to());
        if (c.studentFee().signum() > 0) {
            int months = fullCycleMonths(c, u.from(), u.to());
            BigDecimal share;
            String basis;
            if (months > 0) {
                share = BigDecimal.valueOf(months).divide(BigDecimal.valueOf(12), 8, RoundingMode.HALF_UP);
                basis = months == 12 ? "" : " × " + months + "/12";
            } else {
                long days = ChronoUnit.DAYS.between(u.from(), u.to()) + 1;
                share = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
                basis = " × " + days + "/365 days";
            }
            BigDecimal unit = whole(c.studentFee().multiply(share));
            lines.add(new Line("STUDENTS", "Active students, " + period + " (TZS " + tzs(c.studentFee()) + " per student per year" + basis + ")",
                    BigDecimal.valueOf(u.activeStudents()), unit, unit.multiply(BigDecimal.valueOf(u.activeStudents()))));
        }
        if (c.applicationFee().signum() > 0) {
            lines.add(new Line("APPLICATIONS", "Applications with the fee paid, " + period, BigDecimal.valueOf(u.paidApplications()), c.applicationFee(),
                    c.applicationFee().multiply(BigDecimal.valueOf(u.paidApplications()))));
        }
        if (c.applicationPercent().signum() > 0) {
            BigDecimal share = whole(u.applicationFeesCollected().multiply(c.applicationPercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            lines.add(new Line("APPLICATION_SHARE", c.applicationPercent().stripTrailingZeros().toPlainString() + "% of application fees collected, "
                    + period + " (TZS " + tzs(u.applicationFeesCollected()) + ")", BigDecimal.ONE, share, share));
        }
        return lines;
    }

    /** The months of the period when it is exactly whole usage cycles of the contract; 0 when it is not (a short or odd period). */
    static int fullCycleMonths(Contract c, LocalDate from, LocalDate to) {
        for (int m = 1; m <= 12; m++) {
            if (from.plusMonths(m).minusDays(1).equals(to)) return m;
        }
        return 0;
    }

    static int cycleMonths(String cycle) {
        return switch (cycle) {
            case "MONTHLY" -> 1;
            case "SEMI_ANNUAL" -> 6;
            case "ANNUAL" -> 12;
            default -> 3;
        };
    }

    /** The usage cycle after the last one invoiced (anchored on the contract start), cut at the contract end. */
    static LocalDate[] nextCycle(Contract c) {
        int m = cycleMonths(c.usageCycle());
        LocalDate from = c.usageBilledUntil() == null ? c.startDate() : c.usageBilledUntil().plusDays(1);
        int k = 0;
        LocalDate end = c.startDate().plusMonths(m).minusDays(1);
        while (end.isBefore(from)) {
            k++;
            end = c.startDate().plusMonths((long) (k + 1) * m).minusDays(1);
        }
        if (c.endDate() != null && c.endDate().isBefore(end)) end = c.endDate();
        return new LocalDate[]{from, end};
    }

    private Long newInvoice(Long tenantId, Contract c, String kind, LocalDate from, LocalDate to, BigDecimal vat, boolean generated) {
        return jdbc.queryForObject("""
                insert into billing_invoices (tenant_id, contract_id, kind, period_from, period_to, vat_percent, generated, created_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, tenantId, c == null ? null : c.id(), kind,
                from == null ? null : Date.valueOf(from), to == null ? null : Date.valueOf(to), vat, generated,
                generated ? "CIMS billing" : CurrentUser.platformName());
    }

    private void writeLines(Long invoiceId, Long tenantId, List<Line> lines) {
        jdbc.update("delete from billing_invoice_lines where invoice_id = ?", invoiceId);
        int n = 0;
        for (Line l : lines) {
            jdbc.update("insert into billing_invoice_lines (tenant_id, invoice_id, kind, description, quantity, unit_price, amount, sort_order) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, invoiceId, l.kind(), l.description(), l.quantity(), l.unitPrice(), l.amount(), n++);
        }
        jdbc.update("""
                update billing_invoices i set subtotal = x.s, vat_amount = round(x.s * i.vat_percent / 100, 0), total = x.s + round(x.s * i.vat_percent / 100, 0),
                  updated_at = now() from (select coalesce(sum(amount), 0) s from billing_invoice_lines where invoice_id = ?) x where i.id = ?""", invoiceId, invoiceId);
        BigDecimal total = jdbc.queryForObject("select total from billing_invoices where id = ?", BigDecimal.class, invoiceId);
        if (total.signum() < 0) throw new BusinessRuleException("NEGATIVE_TOTAL", "The invoice total cannot be below zero.");
    }

    // ================================================================= changing, issuing, cancelling

    @Transactional
    public InvoiceView updateDraft(Long id, InvoiceInput in) {
        Map<String, Object> inv = invoice(id);
        if (!"DRAFT".equals(inv.get("status"))) throw new BusinessRuleException("NOT_DRAFT", "Only a draft invoice can be changed.");
        List<Line> lines = new ArrayList<>();
        for (Line l : in.lines() == null ? List.<Line>of() : in.lines()) {
            String kind = l.kind() == null ? "OTHER" : l.kind();
            if (!LINE_KINDS.contains(kind)) throw new BusinessRuleException("INVALID_LINE", "Unknown kind of line " + kind);
            String d = trim(l.description());
            if (d == null) throw new BusinessRuleException("DESCRIPTION_REQUIRED", "Describe every line.");
            BigDecimal q = l.quantity() == null ? BigDecimal.ONE : l.quantity();
            BigDecimal p = l.unitPrice() == null ? BigDecimal.ZERO : l.unitPrice();
            if (q.signum() < 0) throw new BusinessRuleException("INVALID_QUANTITY", "Quantities cannot be negative.");
            if ("DISCOUNT".equals(kind)) p = p.abs().negate();
            else if (p.signum() < 0) throw new BusinessRuleException("INVALID_PRICE", "Use a discount line for an amount taken off.");
            lines.add(new Line(kind, d.length() > 300 ? d.substring(0, 300) : d, q, money(p), whole(q.multiply(p))));
        }
        if (in.vatPercent() != null) {
            if (in.vatPercent().signum() < 0 || in.vatPercent().compareTo(HUNDRED) > 0) throw new BusinessRuleException("INVALID_VAT", "VAT must be between 0 and 100%.");
            jdbc.update("update billing_invoices set vat_percent = ? where id = ?", in.vatPercent(), id);
        }
        jdbc.update("update billing_invoices set notes = ?, version = version + 1 where id = ?", trim(in.notes()), id);
        writeLines(id, ((Number) inv.get("tenant_id")).longValue(), lines);
        return view(id, true);
    }

    /** Numbers the invoice, sets its due date and payment reference, applies any credit, and sends it to the institution. */
    @Transactional
    public InvoiceView issue(Long id) {
        doIssue(id, CurrentUser.platformName());
        return view(id, true);
    }

    void doIssue(Long id, String by) {
        Map<String, Object> inv = invoice(id);
        if (!"DRAFT".equals(inv.get("status"))) throw new BusinessRuleException("NOT_DRAFT", "This invoice has already been issued.");
        BigDecimal total = (BigDecimal) inv.get("total");
        if (total.signum() <= 0) throw new BusinessRuleException("EMPTY_INVOICE", "There is nothing to charge on this invoice. Add a line or cancel it.");
        Long tenantId = ((Number) inv.get("tenant_id")).longValue();
        Contract c = active(tenantId);
        int terms = c == null ? 30 : c.paymentTermsDays();
        LocalDate today = today();
        String no = "MSG-INV-" + today.getYear() + "-" + String.format("%05d", jdbc.queryForObject("select nextval('msingi_invoice_seq')", Long.class));
        String ref = reference(tenantId);
        jdbc.update("""
                update billing_invoices set status = 'ISSUED', invoice_no = ?, issue_date = ?, due_date = ?, payment_reference = ?, issued_by_name = ?,
                  updated_at = now(), version = version + 1 where id = ?""", no, Date.valueOf(today), Date.valueOf(today.plusDays(terms)), ref, by, id);
        applyCredit(tenantId);
        platformAudit.recordAs(CurrentUser.platformId().orElse(null), by, "BILLING_INVOICE_ISSUED", tenantId, "BillingInvoice", id,
                Map.of("invoiceNo", no, "total", total.toPlainString()));
        InvoiceView v = view(id, true);
        BillingDocuments.Pdf pdf = documents.invoice(logo(), v, settings(), tenant(tenantId), c);
        toInstitution(tenantId, c, "Invoice " + no + " from Msingi Technologies",
                "Dear " + addressee(tenantId, c) + ",\n\nPlease find attached invoice " + no + " for TZS " + tzs(v.invoice().total())
                        + (v.invoice().balance().compareTo(v.invoice().total()) < 0 ? " (TZS " + tzs(v.invoice().balance()) + " still to pay after your credit)" : "")
                        + ", due on " + DAY.format(v.invoice().dueDate()) + ".\nWhen paying, quote the payment reference " + ref + "."
                        + payLines() + "\n\nThe invoice and your statement are also under Administration › Msingi subscription in CIMS.",
                pdf);
    }

    /** Cancels an invoice. Payments already applied to it go back to the institution's credit. */
    @Transactional
    public InvoiceView cancel(Long id, String reason) {
        Map<String, Object> inv = invoice(id);
        String status = (String) inv.get("status");
        if ("CANCELLED".equals(status)) throw new BusinessRuleException("ALREADY_CANCELLED", "This invoice is already cancelled.");
        String r = trim(reason);
        if (!"DRAFT".equals(status) && r == null) throw new BusinessRuleException("REASON_REQUIRED", "Give the reason for cancelling an issued invoice.");
        List<Map<String, Object>> alloc = jdbc.queryForList("select id, payment_id, amount from billing_allocations where invoice_id = ?", id);
        for (Map<String, Object> a : alloc) {
            jdbc.update("update billing_payments set unallocated = unallocated + ? where id = ?", a.get("amount"), a.get("payment_id"));
        }
        jdbc.update("delete from billing_allocations where invoice_id = ?", id);
        jdbc.update("update billing_invoices set status = 'CANCELLED', paid_amount = 0, cancelled_reason = ?, updated_at = now(), version = version + 1 where id = ?",
                r == null ? "Draft discarded" : r, id);
        Long tenantId = ((Number) inv.get("tenant_id")).longValue();
        platformAudit.record("BILLING_INVOICE_CANCELLED", tenantId, "BillingInvoice", id, Map.of("invoiceNo", String.valueOf(inv.get("invoice_no")), "reason", r == null ? "draft" : r));
        if (!alloc.isEmpty()) applyCredit(tenantId);
        return view(id, true);
    }

    /** Sends a reminder for an unpaid invoice now. */
    @Transactional
    public InvoiceView remind(Long id) {
        Map<String, Object> inv = invoice(id);
        if (!Set.of("ISSUED", "PARTLY_PAID").contains((String) inv.get("status"))) throw new BusinessRuleException("NOT_OPEN", "Only an unpaid invoice can be reminded.");
        sendReminder(id, 0, "MANUAL");
        return view(id, true);
    }

    // ================================================================= payments

    /** A payment Msingi staff received by bank transfer, mobile money, cheque or cash. */
    @Transactional
    public PaymentRow record(PaymentInput in) {
        tenant(in.tenantId());
        if (in.amount() == null || in.amount().signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "Enter the amount received.");
        String method = in.method() == null ? "BANK" : in.method();
        if (!METHODS.contains(method)) throw new BusinessRuleException("INVALID_METHOD", "Choose how it was paid.");
        LocalDate on = in.paidOn() == null ? today() : in.paidOn();
        if (on.isAfter(today())) throw new BusinessRuleException("FUTURE_DATE", "The payment date cannot be in the future.");
        if (in.invoiceId() != null) {
            Map<String, Object> inv = invoice(in.invoiceId());
            if (((Number) inv.get("tenant_id")).longValue() != in.tenantId()) throw new BusinessRuleException("WRONG_INSTITUTION", "That invoice belongs to another institution.");
        }
        OffsetDateTime at = on.equals(today()) ? OffsetDateTime.now() : on.atTime(12, 0).atZone(TZ).toOffsetDateTime();
        Long id = receive(in.tenantId(), money(in.amount()), at, method, trim(in.provider()), trim(in.reference()), null, "MANUAL", trim(in.note()),
                in.invoiceId(), CurrentUser.platformName());
        return payment(id);
    }

    /**
     * A payment reported by Msingi's collector, quoting an invoice's payment reference. A repeated report (same provider and
     * transaction) changes nothing. Returns the payment, or null when the reference is unknown.
     */
    @Transactional
    public PaymentRow online(String reference, String provider, String transactionId, BigDecimal amount, OffsetDateTime paidAt) {
        String ref = reference == null ? null : reference.replaceAll("\\s", "");
        if (!validReference(ref)) return null;
        List<Map<String, Object>> inv = jdbc.queryForList("select id, tenant_id from billing_invoices where payment_reference = ?", ref);
        if (inv.isEmpty()) return null;
        String key = (provider == null ? "COL" : provider.trim().toUpperCase(Locale.ROOT)) + ":" + transactionId.trim();
        List<Long> seen = jdbc.queryForList("select id from billing_payments where transaction_key = ?", Long.class, key);
        if (!seen.isEmpty()) return payment(seen.get(0));
        if (amount == null || amount.signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "The amount must be above zero.");
        PaymentProvider p = provider(provider);
        String method = p == null ? "OTHER" : p.kind() == PaymentProvider.Kind.BANK ? "BANK" : "MOBILE_MONEY";
        Long id = receive(((Number) inv.get(0).get("tenant_id")).longValue(), money(amount), paidAt == null ? OffsetDateTime.now() : paidAt, method,
                p == null ? trim(provider) : p.name(), transactionId.trim(), key, "ONLINE", null, ((Number) inv.get(0).get("id")).longValue(), "Payment collector");
        return payment(id);
    }

    /** "Pay now" while testing: the institution's administrator pays an invoice as if through mobile money. */
    @Transactional
    public PaymentRow simulate(Long invoiceId) {
        if (!allowSimulation) throw new BusinessRuleException("SIMULATION_OFF", "Payment simulation is switched off.");
        Map<String, Object> inv = invoice(invoiceId);
        if (!Set.of("ISSUED", "PARTLY_PAID").contains((String) inv.get("status"))) throw new BusinessRuleException("NOT_OPEN", "This invoice has nothing left to pay.");
        BigDecimal balance = ((BigDecimal) inv.get("total")).subtract((BigDecimal) inv.get("paid_amount"));
        return online((String) inv.get("payment_reference"), "MPESA", "SIM" + System.currentTimeMillis() + RANDOM.nextInt(1000), balance, OffsetDateTime.now());
    }

    private Long receive(Long tenantId, BigDecimal amount, OffsetDateTime at, String method, String provider, String reference, String key,
                         String source, String note, Long invoiceId, String by) {
        String no = "MSG-RCT-" + today().getYear() + "-" + String.format("%05d", jdbc.queryForObject("select nextval('msingi_receipt_seq')", Long.class));
        Long id = jdbc.queryForObject("""
                insert into billing_payments (tenant_id, receipt_no, amount, unallocated, paid_at, method, provider, reference, transaction_key, source, note, recorded_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, tenantId, no, amount, amount, Timestamp.from(at.toInstant()),
                method, provider, reference, key, source, note, by);
        if (invoiceId != null) allocate(id, invoiceId);
        applyCredit(tenantId);
        platformAudit.recordAs(CurrentUser.platformId().orElse(null), by, "BILLING_PAYMENT_RECEIVED", tenantId, "BillingPayment", id,
                Map.of("receiptNo", no, "amount", amount.toPlainString(), "source", source));
        PaymentRow p = payment(id);
        Contract c = active(tenantId);
        toInstitution(tenantId, c, "Payment received — receipt " + no, "Dear " + addressee(tenantId, c) + ",\n\nMsingi Technologies has received TZS "
                + tzs(amount) + " (" + DAY.format(at.atZoneSameInstant(TZ).toLocalDate()) + ")" + (p.invoices() == null || p.invoices().isBlank() ? "" : " for " + p.invoices())
                + ". Thank you.\nYour receipt is attached." + (p.unallocated().signum() > 0 ? " TZS " + tzs(p.unallocated()) + " is held as credit for your next invoice." : ""),
                documents.receipt(logo(), p, settings(), tenant(tenantId)));
        return id;
    }

    /** Applies what is left of a payment to an invoice (up to its balance). */
    private void allocate(Long paymentId, Long invoiceId) {
        Map<String, Object> p = jdbc.queryForMap("select tenant_id, unallocated, status from billing_payments where id = ?", paymentId);
        if (!"RECEIVED".equals(p.get("status"))) return;
        Map<String, Object> inv = invoice(invoiceId);
        if (!Set.of("ISSUED", "PARTLY_PAID").contains((String) inv.get("status"))) return;
        BigDecimal balance = ((BigDecimal) inv.get("total")).subtract((BigDecimal) inv.get("paid_amount"));
        BigDecimal take = ((BigDecimal) p.get("unallocated")).min(balance);
        if (take.signum() <= 0) return;
        jdbc.update("insert into billing_allocations (tenant_id, payment_id, invoice_id, amount) values (?, ?, ?, ?)", p.get("tenant_id"), paymentId, invoiceId, take);
        jdbc.update("update billing_payments set unallocated = unallocated - ? where id = ?", take, paymentId);
        refreshPaid(invoiceId);
    }

    /** Applies the institution's credit (what is left of its payments, oldest first) to its open invoices, oldest due first. */
    void applyCredit(Long tenantId) {
        List<Long> pays = jdbc.queryForList("select id from billing_payments where tenant_id = ? and status = 'RECEIVED' and unallocated > 0 order by paid_at, id", Long.class, tenantId);
        if (pays.isEmpty()) return;
        List<Long> open = jdbc.queryForList("select id from billing_invoices where tenant_id = ? and status in ('ISSUED', 'PARTLY_PAID') order by due_date, id", Long.class, tenantId);
        for (Long inv : open) for (Long p : pays) allocate(p, inv);
    }

    private void refreshPaid(Long invoiceId) {
        jdbc.update("""
                update billing_invoices i set paid_amount = x.paid,
                  status = case when i.status in ('DRAFT', 'CANCELLED') then i.status when x.paid >= i.total then 'PAID' when x.paid > 0 then 'PARTLY_PAID' else 'ISSUED' end,
                  updated_at = now()
                from (select coalesce(sum(a.amount), 0) paid from billing_allocations a join billing_payments p on p.id = a.payment_id
                      where a.invoice_id = ? and p.status = 'RECEIVED') x where i.id = ?""", invoiceId, invoiceId);
    }

    /** Reverses a payment recorded by mistake (or bounced): its amounts come off the invoices it paid. */
    @Transactional
    public PaymentRow reverse(Long paymentId, String reason) {
        PaymentRow p = payment(paymentId);
        if ("REVERSED".equals(p.status())) throw new BusinessRuleException("ALREADY_REVERSED", "This payment is already reversed.");
        String r = trim(reason);
        if (r == null) throw new BusinessRuleException("REASON_REQUIRED", "Give the reason for reversing the payment.");
        List<Long> invoices = jdbc.queryForList("select invoice_id from billing_allocations where payment_id = ?", Long.class, paymentId);
        jdbc.update("delete from billing_allocations where payment_id = ?", paymentId);
        jdbc.update("update billing_payments set status = 'REVERSED', unallocated = 0, reversed_reason = ? where id = ?", r, paymentId);
        invoices.forEach(this::refreshPaid);
        platformAudit.record("BILLING_PAYMENT_REVERSED", p.tenantId(), "BillingPayment", paymentId, Map.of("receiptNo", p.receiptNo(), "reason", r));
        return payment(paymentId);
    }

    // ================================================================= reading

    public record InvoicePage(List<InvoiceRow> rows, long total) {
    }

    @Transactional(readOnly = true)
    public InvoicePage invoices(String status, Long tenantId, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        switch (status == null ? "all" : status) {
            case "open" -> where.append(" and i.status in ('ISSUED', 'PARTLY_PAID')");
            case "overdue" -> where.append(" and i.status in ('ISSUED', 'PARTLY_PAID') and i.due_date < current_date");
            case "draft" -> where.append(" and i.status = 'DRAFT'");
            case "paid" -> where.append(" and i.status = 'PAID'");
            case "cancelled" -> where.append(" and i.status = 'CANCELLED'");
            default -> { }
        }
        if (tenantId != null) { where.append(" and i.tenant_id = ?"); args.add(tenantId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (i.invoice_no ilike ? or tn.name ilike ? or tn.code ilike ? or i.payment_reference like ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        long total = jdbc.queryForObject("select count(*) from billing_invoices i join tenants tn on tn.id = i.tenant_id" + where, Long.class, args.toArray());
        int s = Math.min(Math.max(size, 1), 100);
        args.add(s); args.add((long) Math.max(page, 0) * s);
        List<InvoiceRow> rows = jdbc.query(INVOICE + where + " order by case i.status when 'DRAFT' then 0 else 1 end, coalesce(i.issue_date, i.created_at::date) desc, i.id desc limit ? offset ?",
                INVOICE_ROW, args.toArray());
        return new InvoicePage(rows, total);
    }

    @Transactional(readOnly = true)
    public InvoiceView invoiceView(Long id) {
        return view(id, true);
    }

    InvoiceView view(Long id, boolean msingi) {
        List<InvoiceRow> r = jdbc.query(INVOICE + " where i.id = ?", INVOICE_ROW, id);
        if (r.isEmpty() || (!msingi && "DRAFT".equals(r.get(0).status()))) throw new NotFoundException("Invoice", id);
        Map<String, Object> x = jdbc.queryForMap("select subtotal, vat_percent, vat_amount, notes, cancelled_reason, created_by_name, issued_by_name from billing_invoices where id = ?", id);
        List<Line> lines = jdbc.query("select kind, description, quantity, unit_price, amount from billing_invoice_lines where invoice_id = ? order by sort_order, id",
                (rs, i) -> new Line(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)), id);
        List<Applied> pays = jdbc.query("""
                select p.id, p.receipt_no, p.paid_at, p.method, a.amount from billing_allocations a join billing_payments p on p.id = a.payment_id
                where a.invoice_id = ? and p.status = 'RECEIVED' order by p.paid_at""",
                (rs, i) -> new Applied(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getBigDecimal(5)), id);
        List<Reminder> rem = jdbc.query("select level, kind, sent_to, created_at from billing_reminders where invoice_id = ? order by created_at",
                (rs, i) -> new Reminder(rs.getInt(1), rs.getString(2), msingi ? rs.getString(3) : null, rs.getObject(4, OffsetDateTime.class)), id);
        return new InvoiceView(r.get(0), lines, (BigDecimal) x.get("subtotal"), (BigDecimal) x.get("vat_percent"), (BigDecimal) x.get("vat_amount"),
                (String) x.get("notes"), pays, rem, (String) x.get("cancelled_reason"), (String) x.get("created_by_name"), (String) x.get("issued_by_name"));
    }

    @Transactional(readOnly = true)
    public List<PaymentRow> payments(Long tenantId, String q) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) { where.append(" and p.tenant_id = ?"); args.add(tenantId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (p.receipt_no ilike ? or p.reference ilike ? or tn.name ilike ? or tn.code ilike ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        return jdbc.query(PAYMENT + where + " order by p.paid_at desc, p.id desc limit 500", PAYMENT_ROW, args.toArray());
    }

    PaymentRow payment(Long id) {
        List<PaymentRow> r = jdbc.query(PAYMENT + " where p.id = ?", PAYMENT_ROW, id);
        if (r.isEmpty()) throw new NotFoundException("Payment", id);
        return r.get(0);
    }

    /** Invoices and payments of the institution in date order, with the running balance and how old the unpaid amounts are. */
    @Transactional(readOnly = true)
    public Statement statement(Long tenantId) {
        Tenant t = tenant(tenantId);
        List<Entry> entries = new ArrayList<>();
        record Raw(LocalDate date, int order, String kind, String ref, String text, BigDecimal debit, BigDecimal credit) {
        }
        List<Raw> raw = new ArrayList<>();
        jdbc.query("select issue_date, invoice_no, kind, period_from, period_to, total from billing_invoices where tenant_id = ? and status not in ('DRAFT', 'CANCELLED')",
                rs -> {
                    String kind = rs.getString(3);
                    LocalDate f = rs.getObject(4, LocalDate.class), to = rs.getObject(5, LocalDate.class);
                    String what = switch (kind) { case "LICENCE" -> "Licence"; case "USAGE" -> "Usage"; default -> "Invoice"; }
                            + (f != null && to != null ? ", " + DAY.format(f) + " – " + DAY.format(to) : "");
                    raw.add(new Raw(rs.getObject(1, LocalDate.class), 0, "INVOICE", rs.getString(2), what, rs.getBigDecimal(6), BigDecimal.ZERO));
                }, tenantId);
        jdbc.query("select paid_at, receipt_no, method, provider, reference, amount, status from billing_payments where tenant_id = ?", rs -> {
            LocalDate d = rs.getObject(1, OffsetDateTime.class).atZoneSameInstant(TZ).toLocalDate();
            String text = "Payment, " + methodLabel(rs.getString(3)) + (rs.getString(5) == null ? "" : " (" + rs.getString(5) + ")");
            raw.add(new Raw(d, 1, "PAYMENT", rs.getString(2), text, BigDecimal.ZERO, rs.getBigDecimal(6)));
            if ("REVERSED".equals(rs.getString(7))) raw.add(new Raw(d, 2, "REVERSAL", rs.getString(2), "Payment reversed", rs.getBigDecimal(6), BigDecimal.ZERO));
        }, tenantId);
        raw.sort(java.util.Comparator.comparing(Raw::date).thenComparing(Raw::order));
        BigDecimal bal = BigDecimal.ZERO, invoiced = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        for (Raw x : raw) {
            bal = bal.add(x.debit()).subtract(x.credit());
            if ("INVOICE".equals(x.kind())) invoiced = invoiced.add(x.debit());
            if ("PAYMENT".equals(x.kind())) paid = paid.add(x.credit());
            if ("REVERSAL".equals(x.kind())) paid = paid.subtract(x.debit());
            entries.add(new Entry(x.date(), x.kind(), x.ref(), x.text(), x.debit(), x.credit(), bal));
        }
        BigDecimal credit = credit(tenantId);
        return new Statement(tenantId, t.getCode(), t.getName(), active(tenantId), entries, invoiced, paid, bal, credit, aging(tenantId));
    }

    BigDecimal credit(Long tenantId) {
        return jdbc.queryForObject("select coalesce(sum(unallocated), 0) from billing_payments where tenant_id = ? and status = 'RECEIVED'", BigDecimal.class, tenantId);
    }

    Aging aging(Long tenantId) {
        Map<String, Object> a = jdbc.queryForMap("""
                select coalesce(sum(b) filter (where d <= 0), 0) c, coalesce(sum(b) filter (where d between 1 and 30), 0) a1,
                       coalesce(sum(b) filter (where d between 31 and 60), 0) a2, coalesce(sum(b) filter (where d between 61 and 90), 0) a3,
                       coalesce(sum(b) filter (where d > 90), 0) a4
                from (select total - paid_amount b, current_date - due_date d from billing_invoices
                      where status in ('ISSUED', 'PARTLY_PAID') and (cast(? as bigint) is null or tenant_id = ?)) x""", tenantId, tenantId);
        return new Aging((BigDecimal) a.get("c"), (BigDecimal) a.get("a1"), (BigDecimal) a.get("a2"), (BigDecimal) a.get("a3"), (BigDecimal) a.get("a4"));
    }

    @Transactional(readOnly = true)
    public Overview overview(int year) {
        Map<String, Object> t = jdbc.queryForMap("""
                select (select coalesce(sum(total), 0) from billing_invoices where status not in ('DRAFT', 'CANCELLED') and extract(year from issue_date) = ?) invoiced,
                       (select coalesce(sum(amount), 0) from billing_payments where status = 'RECEIVED' and extract(year from paid_at at time zone 'Africa/Dar_es_Salaam') = ?) collected,
                       (select coalesce(sum(total - paid_amount), 0) from billing_invoices where status in ('ISSUED', 'PARTLY_PAID')) outstanding,
                       (select coalesce(sum(total - paid_amount), 0) from billing_invoices where status in ('ISSUED', 'PARTLY_PAID') and due_date < current_date) overdue,
                       (select count(*) from billing_invoices where status = 'DRAFT') drafts,
                       (select count(*) from billing_contracts where status = 'ACTIVE') contracts""", year, year);
        List<InstitutionSummary> rows = jdbc.query("""
                select tn.id, tn.code, tn.name, tn.status, c.status, c.annual_fee,
                       coalesce((select sum(total - paid_amount) from billing_invoices i where i.tenant_id = tn.id and i.status in ('ISSUED', 'PARTLY_PAID')), 0),
                       coalesce((select sum(total - paid_amount) from billing_invoices i where i.tenant_id = tn.id and i.status in ('ISSUED', 'PARTLY_PAID') and i.due_date < current_date), 0),
                       (select max(current_date - due_date) from billing_invoices i where i.tenant_id = tn.id and i.status in ('ISSUED', 'PARTLY_PAID') and i.due_date < current_date),
                       coalesce((select sum(unallocated) from billing_payments p where p.tenant_id = tn.id and p.status = 'RECEIVED'), 0),
                       (select max(paid_at) from billing_payments p where p.tenant_id = tn.id and p.status = 'RECEIVED'),
                       (select count(*) from billing_invoices i where i.tenant_id = tn.id and i.status = 'DRAFT')
                from tenants tn left join billing_contracts c on c.tenant_id = tn.id and c.status = 'ACTIVE'
                order by 8 desc, 7 desc, tn.name""", (rs, i) -> new InstitutionSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), (Integer) rs.getObject(9), rs.getBigDecimal(10),
                rs.getObject(11, OffsetDateTime.class), rs.getLong(12)));
        return new Overview(year, (BigDecimal) t.get("invoiced"), (BigDecimal) t.get("collected"), (BigDecimal) t.get("outstanding"),
                (BigDecimal) t.get("overdue"), ((Number) t.get("drafts")).longValue(), ((Number) t.get("contracts")).longValue(), aging(null), rows);
    }

    // ================================================================= the institution's own view

    @Transactional(readOnly = true)
    public Account account() {
        Long tenantId = TenantContext.requireTenantId();
        Contract c = active(tenantId);
        List<InvoiceRow> inv = jdbc.query(INVOICE + " where i.tenant_id = ? and i.status <> 'DRAFT' order by i.issue_date desc, i.id desc", INVOICE_ROW, tenantId);
        BigDecimal balance = inv.stream().filter(r -> Set.of("ISSUED", "PARTLY_PAID").contains(r.status())).map(InvoiceRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdue = inv.stream().filter(r -> r.daysOverdue() != null && r.daysOverdue() > 0).map(InvoiceRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        Settings s = settings();
        Settings payTo = new Settings(s.companyName(), s.tin(), s.vrn(), s.address(), s.phone(), s.email(), s.website(), s.channels(), s.invoiceNote(), null, null, s.customLogo());
        Contract mine = c == null ? null : new Contract(c.id(), c.tenantId(), c.reference(), c.status(), c.startDate(), c.endDate(), c.currency(), c.setupFee(),
                c.annualFee(), c.studentFee(), c.applicationFee(), c.applicationPercent(), c.usageCycle(), c.vatPercent(), c.paymentTermsDays(), false, false,
                c.suspendAfterDays(), c.billingContactName(), c.billingEmail(), c.billingPhone(), null, c.setupBilled(), c.licenceBilledUntil(), c.usageBilledUntil(), null, null);
        return new Account(mine, balance, overdue, credit(tenantId), inv, payments(tenantId, null), payTo, allowSimulation);
    }

    @Transactional(readOnly = true)
    public InvoiceView ownInvoice(Long id) {
        InvoiceView v = view(id, false);
        if (!v.invoice().tenantId().equals(TenantContext.requireTenantId())) throw new NotFoundException("Invoice", id);
        return v;
    }

    @Transactional(readOnly = true)
    public BillingDocuments.Pdf invoicePdf(Long id, boolean msingi) {
        InvoiceView v = msingi ? view(id, true) : ownInvoice(id);
        return documents.invoice(logo(), v, settings(), tenant(v.invoice().tenantId()), active(v.invoice().tenantId()));
    }

    @Transactional(readOnly = true)
    public BillingDocuments.Pdf receiptPdf(Long paymentId, boolean msingi) {
        PaymentRow p = payment(paymentId);
        if (!msingi && !p.tenantId().equals(TenantContext.requireTenantId())) throw new NotFoundException("Payment", paymentId);
        return documents.receipt(logo(), p, settings(), tenant(p.tenantId()));
    }

    @Transactional(readOnly = true)
    public BillingDocuments.Pdf statementPdf(Long tenantId) {
        return documents.statement(logo(), statement(tenantId), settings());
    }

    @Transactional
    public PaymentRow simulateOwn(Long invoiceId) {
        ownInvoice(invoiceId);
        return simulate(invoiceId);
    }

    // ================================================================= reminders (used by the job)

    /** Emails the institution about an unpaid invoice and logs it; level 0 is a reminder sent by hand. */
    void sendReminder(Long invoiceId, int level, String kind) {
        InvoiceView v = view(invoiceId, true);
        InvoiceRow r = v.invoice();
        Contract c = active(r.tenantId());
        String subject = switch (kind) {
            case "DUE_SOON" -> "Invoice " + r.invoiceNo() + " is due on " + DAY.format(r.dueDate());
            case "SUSPENSION_DUE", "SUSPENDED" -> "Final notice: invoice " + r.invoiceNo() + " is " + r.daysOverdue() + " days overdue";
            default -> r.daysOverdue() != null && r.daysOverdue() > 0 ? "Invoice " + r.invoiceNo() + " is overdue" : "Reminder: invoice " + r.invoiceNo();
        };
        String body = "Dear " + addressee(r.tenantId(), c) + ",\n\n" + switch (kind) {
            case "DUE_SOON" -> "This is a reminder that invoice " + r.invoiceNo() + " (TZS " + tzs(r.balance()) + " to pay) is due on " + DAY.format(r.dueDate()) + ".";
            case "SUSPENDED" -> "Invoice " + r.invoiceNo() + " (TZS " + tzs(r.balance()) + ") was due on " + DAY.format(r.dueDate())
                    + " and is still unpaid, so CIMS access for your institution has been suspended as set out in your contract. It is restored as soon as the payment is received.";
            case "SUSPENSION_DUE" -> "Invoice " + r.invoiceNo() + " (TZS " + tzs(r.balance()) + ") was due on " + DAY.format(r.dueDate())
                    + " and is still unpaid. Please pay it now to avoid interruption of your CIMS service.";
            default -> "Invoice " + r.invoiceNo() + " (TZS " + tzs(r.balance()) + " to pay)" + (r.daysOverdue() != null && r.daysOverdue() > 0
                    ? " was due on " + DAY.format(r.dueDate()) + " and is " + r.daysOverdue() + " days overdue." : " is due on " + DAY.format(r.dueDate()) + ".");
        } + "\nWhen paying, quote the payment reference " + r.paymentReference() + "." + payLines()
                + "\n\nIf you have already paid, please send us the payment details so we can match it.";
        String to = toInstitution(r.tenantId(), c, subject, body, documents.invoice(logo(), v, settings(), tenant(r.tenantId()), c));
        jdbc.update("insert into billing_reminders (tenant_id, invoice_id, level, kind, sent_to) values (?, ?, ?, ?, ?)", r.tenantId(), invoiceId, level, kind, to);
        jdbc.update("update billing_invoices set last_reminded_at = now(), reminder_level = greatest(reminder_level, ?) where id = ?", level, invoiceId);
    }

    /** Emails all active Msingi staff (from the institution's outbox, where email lives). */
    void toMsingi(Long tenantId, String subject, String body) {
        List<Map<String, Object>> to = jdbc.queryForList("select email, full_name from platform_users where status = 'ACTIVE' and email is not null");
        if (to.isEmpty()) return;
        asInstitution(tenantId, () -> to.forEach(r -> email.queue((String) r.get("email"), (String) r.get("full_name"), EmailMessage.Kind.EVENT, "MSINGI_BILLING",
                subject, body + "\n\n— CIMS billing", null)));
    }

    // ================================================================= helpers

    /** Emails the institution's billing contact (or its contact email); returns where it went. */
    private String toInstitution(Long tenantId, Contract c, String subject, String body, BillingDocuments.Pdf pdf) {
        Tenant t = tenant(tenantId);
        String to = c != null && c.billingEmail() != null ? c.billingEmail() : t.getContactEmail();
        if (to == null || to.isBlank()) return null;
        String name = c != null && c.billingContactName() != null ? c.billingContactName() : t.getName();
        asInstitution(tenantId, () -> email.queue(to, name, EmailMessage.Kind.EVENT, "MSINGI_BILLING", subject,
                body + "\n\nMsingi Technologies Ltd", pdf == null ? null : new EmailService.Attachment(pdf.fileName(), "application/pdf", pdf.content())));
        return to;
    }

    private String addressee(Long tenantId, Contract c) {
        return c != null && c.billingContactName() != null ? c.billingContactName() : tenant(tenantId).getName();
    }

    private String payLines() {
        List<Channel> ch = settings().channels();
        if (ch.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\nPay to:");
        for (Channel c : ch) {
            PaymentProvider p = provider(c.provider());
            b.append("\n  • ").append(p == null ? c.provider() : p.label()).append(" ").append(c.accountNumber())
                    .append(c.accountName() == null ? "" : " (" + c.accountName() + ")").append(c.branch() == null ? "" : ", " + c.branch());
        }
        return b.toString();
    }

    private void asInstitution(Long tenantId, Runnable work) {
        if (TenantContext.currentTenantIdOrNull() == null) tenantWork.within(tenant(tenantId), () -> { work.run(); return null; });
        else work.run();
    }

    Tenant tenant(Long id) {
        if (id == null) throw new BusinessRuleException("INSTITUTION_REQUIRED", "Choose the institution.");
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("Institution", id));
    }

    private Map<String, Object> invoice(Long id) {
        return jdbc.queryForList("select * from billing_invoices where id = ?", id).stream().findFirst().orElseThrow(() -> new NotFoundException("Invoice", id));
    }

    /** "7", the institution in four digits, six random digits and a check digit (Luhn): 12 digits. */
    private String reference(Long tenantId) {
        for (int i = 0; i < 20; i++) {
            String body = "7" + String.format("%04d", tenantId % 10000) + String.format("%06d", RANDOM.nextInt(1_000_000));
            String ref = body + luhn(body);
            if (jdbc.queryForObject("select count(*) from billing_invoices where payment_reference = ?", Integer.class, ref) == 0) return ref;
        }
        throw new IllegalStateException("No free payment reference");
    }

    static boolean validReference(String ref) {
        return ref != null && ref.matches("7\\d{11}") && luhn(ref.substring(0, 11)) == ref.charAt(11) - '0';
    }

    static int luhn(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    static PaymentProvider provider(String code) {
        if (code == null) return null;
        try {
            return PaymentProvider.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String methodLabel(String m) {
        return switch (m) {
            case "BANK" -> "bank transfer";
            case "MOBILE_MONEY" -> "mobile money";
            case "CHEQUE" -> "cheque";
            case "CASH" -> "cash";
            default -> "other";
        };
    }

    static BigDecimal money(BigDecimal b) {
        return b == null ? BigDecimal.ZERO.setScale(2) : b.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal whole(BigDecimal b) {
        return b.setScale(0, RoundingMode.HALF_UP).setScale(2);
    }

    static String tzs(BigDecimal amount) {
        BigDecimal a = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", a.longValue()) : String.format(Locale.ENGLISH, "%,.2f", a);
    }

    static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Object[] append(Object[] a, Object... more) {
        Object[] out = java.util.Arrays.copyOf(a, a.length + more.length);
        System.arraycopy(more, 0, out, a.length, more.length);
        return out;
    }

    private List<Channel> channels(String s) {
        try {
            return s == null ? List.of() : json.readValue(s, new TypeReference<List<Channel>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- rows

    private static final String CONTRACT = """
            select id, tenant_id, reference, status, start_date, end_date, currency, setup_fee, annual_fee, student_fee, application_fee,
                   application_percent, usage_cycle, vat_percent, payment_terms_days, auto_issue, auto_suspend, suspend_after_days, billing_contact_name,
                   billing_email, billing_phone, notes, setup_billed, licence_billed_until, usage_billed_until, created_by_name, created_at
            from billing_contracts""";

    private static final RowMapper<Contract> CONTRACT_ROW = (rs, i) -> new Contract(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
            rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13), rs.getBigDecimal(14), rs.getInt(15), rs.getBoolean(16), rs.getBoolean(17), rs.getInt(18),
            rs.getString(19), rs.getString(20), rs.getString(21), rs.getString(22), rs.getBoolean(23), rs.getObject(24, LocalDate.class),
            rs.getObject(25, LocalDate.class), rs.getString(26), rs.getObject(27, OffsetDateTime.class));

    static final String INVOICE = """
            select i.id, i.tenant_id, tn.code, tn.name, i.invoice_no, i.kind, i.period_from, i.period_to, i.status, i.currency, i.total, i.paid_amount,
                   i.total - i.paid_amount, i.issue_date, i.due_date,
                   case when i.status in ('ISSUED', 'PARTLY_PAID') then current_date - i.due_date end, i.payment_reference, i.generated, i.created_at
            from billing_invoices i join tenants tn on tn.id = i.tenant_id""";

    static final RowMapper<InvoiceRow> INVOICE_ROW = (rs, i) -> new InvoiceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getString(9), rs.getString(10), rs.getBigDecimal(11),
            rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getObject(14, LocalDate.class), rs.getObject(15, LocalDate.class), (Integer) rs.getObject(16),
            rs.getString(17), rs.getBoolean(18), rs.getObject(19, OffsetDateTime.class));

    private static final String PAYMENT = """
            select p.id, p.tenant_id, tn.code, tn.name, p.receipt_no, p.amount, p.unallocated, p.paid_at, p.method, p.provider, p.reference, p.source,
                   p.status, p.recorded_by_name,
                   (select string_agg(i.invoice_no, ', ' order by i.invoice_no) from billing_allocations a join billing_invoices i on i.id = a.invoice_id where a.payment_id = p.id),
                   p.reversed_reason
            from billing_payments p join tenants tn on tn.id = p.tenant_id""";

    private static final RowMapper<PaymentRow> PAYMENT_ROW = (rs, i) -> new PaymentRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16));

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }
}
