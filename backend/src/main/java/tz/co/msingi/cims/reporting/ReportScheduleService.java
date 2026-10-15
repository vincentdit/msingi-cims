package tz.co.msingi.cims.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.email.EmailMessage;
import tz.co.msingi.cims.notification.email.EmailService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.RunAsStaff;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports emailed on a schedule (FR-RPT-03). A staff member chooses a report they may run, its filters, PDF or Excel, how often and to
 * whom; recipients are staff of the institution with a verified email (personal data never leaves to outside addresses). Each run uses
 * the owner's roles and data scope, so a schedule sends nothing its owner could not see; exports are in the audit trail as usual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportScheduleService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final EmailService email;
    private final UserAccountRepository users;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public record ScheduleRequest(@NotBlank @Size(max = 20) String reportCode, @NotNull Map<String, Object> filters,
                                  @NotBlank @Pattern(regexp = "PDF|XLSX") String format, @NotBlank @Pattern(regexp = "DAILY|WEEKLY|MONTHLY") String frequency,
                                  @Min(1) @Max(7) Integer dayOfWeek, @Min(1) @Max(28) Integer dayOfMonth, @Min(0) @Max(23) int sendHour,
                                  @NotEmpty @Size(max = 20) List<@NotBlank String> recipients, boolean active) {
    }

    public record ScheduleView(Long id, String reportCode, String title, Map<String, Object> filters, String format, String frequency,
                               Integer dayOfWeek, Integer dayOfMonth, int sendHour, List<String> recipients, String ownerName, boolean mine,
                               boolean active, OffsetDateTime nextRunAt, OffsetDateTime lastRunAt, String lastStatus) {
    }

    public record Recipient(Long userId, String name, String email) {
    }

    // ------------------------------------------------------------------ managing schedules

    /** Staff who can receive scheduled reports: active staff with a verified email. */
    @Transactional(readOnly = true)
    public List<Recipient> recipients() {
        return jdbc.query("""
                select id, full_name, email from user_accounts where user_type = 'STAFF' and status = 'ACTIVE' and email is not null
                  and email_verified_at is not null order by full_name""", (rs, i) -> new Recipient(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    @Transactional(readOnly = true)
    public List<ScheduleView> list() {
        Long me = CurrentUser.requireId();
        return jdbc.query("select * from report_schedules order by title", (rs, i) -> view(rs, me));
    }

    @Transactional
    public ScheduleView save(Long id, ScheduleRequest req) {
        Long me = CurrentUser.requireId();
        ReportModel.CatalogueEntry entry = reports.catalogue().stream().filter(c -> c.code().equals(req.reportCode())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("REPORT_NOT_ALLOWED", "You cannot run report " + req.reportCode()));
        filter(req.filters());   // checks the filters are well formed
        if ("WEEKLY".equals(req.frequency()) && req.dayOfWeek() == null) throw new BusinessRuleException("DAY_REQUIRED", "Choose the day of the week");
        if ("MONTHLY".equals(req.frequency()) && req.dayOfMonth() == null) throw new BusinessRuleException("DAY_REQUIRED", "Choose the day of the month (1–28)");
        Set<String> allowed = new java.util.HashSet<>(recipients().stream().map(r -> r.email().toLowerCase(Locale.ROOT)).toList());
        Set<String> to = new LinkedHashSet<>();
        for (String r : req.recipients()) {
            String e = r.trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(e)) {
                throw new BusinessRuleException("RECIPIENT_NOT_ALLOWED", e + " is not a staff member with a verified email; reports go only to staff");
            }
            to.add(e);
        }
        OffsetDateTime next = nextRun(req.frequency(), req.dayOfWeek(), req.dayOfMonth(), req.sendHour(), ZonedDateTime.now(ZONE));
        String filtersJson = json(req.filters());
        String title = entry.code() + " — " + entry.title();
        if (id == null) {
            id = jdbc.queryForObject("""
                    insert into report_schedules (tenant_id, report_code, title, filters, format, frequency, day_of_week, day_of_month, send_hour,
                      recipients, owner_id, owner_name, active, next_run_at)
                    values (cims_current_tenant(), ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                    req.reportCode(), title, filtersJson, req.format(), req.frequency(), dayOfWeek(req), dayOfMonth(req), req.sendHour(),
                    String.join(",", to), me, CurrentUser.name(), req.active(), next);
        } else {
            requireOwnerOrAdmin(id);
            jdbc.update("""
                    update report_schedules set report_code = ?, title = ?, filters = ?::jsonb, format = ?, frequency = ?, day_of_week = ?, day_of_month = ?,
                      send_hour = ?, recipients = ?, active = ?, next_run_at = ?, updated_at = now(), version = version + 1 where id = ?""",
                    req.reportCode(), title, filtersJson, req.format(), req.frequency(), dayOfWeek(req), dayOfMonth(req), req.sendHour(),
                    String.join(",", to), req.active(), next, id);
        }
        audit.record("REPORT_SCHEDULE_SAVED", "ReportSchedule", id, Map.of("report", req.reportCode(), "frequency", req.frequency(),
                "recipients", to.size(), "active", req.active()));
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        requireOwnerOrAdmin(id);
        jdbc.update("delete from report_schedules where id = ?", id);
        audit.record("REPORT_SCHEDULE_DELETED", "ReportSchedule", id, Map.of());
    }

    /** Sends one schedule now (as its owner), without changing when it next runs. */
    @Transactional
    public ScheduleView sendNow(Long id) {
        requireOwnerOrAdmin(id);
        runOne(id, false);
        return get(id);
    }

    // ------------------------------------------------------------------ running

    @Scheduled(cron = "${cims.reports.schedule-cron:0 */10 * * * *}", zone = "Africa/Dar_es_Salaam")
    public void runDue() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> {
                    List<Long> due = tx.execute(s -> jdbc.queryForList("select id from report_schedules where active and next_run_at <= now() order by next_run_at", Long.class));
                    if (due != null) due.forEach(id -> tx.executeWithoutResult(s -> runOne(id, true)));
                });
            } catch (RuntimeException e) {
                log.error("Scheduled reports failed for institution {}", t.getCode(), e);
            }
        }
    }

    private void runOne(Long id, boolean advance) {
        Map<String, Object> s = jdbc.queryForMap("select * from report_schedules where id = ?", id);
        String status;
        try {
            UserAccount owner = users.findById(((Number) s.get("owner_id")).longValue()).orElse(null);
            if (owner == null || owner.getStatus() != UserAccount.Status.ACTIVE) {
                throw new BusinessRuleException("OWNER_INACTIVE", "The owner's account is no longer active");
            }
            Filter filter = filter(mapper.readValue(String.valueOf(s.get("filters")), Map.class));
            String code = (String) s.get("report_code");
            String format = ((String) s.get("format")).toLowerCase(Locale.ROOT);
            ReportService.Export export = RunAsStaff.call(owner, () -> reports.export(code, filter, format));
            List<String> to = List.of(((String) s.get("recipients")).split(","));
            String institution = tenants.findById(TenantContext.requireTenantId()).map(Tenant::getName).orElse("CIMS");
            for (String r : to) {
                email.queue(r, null, EmailMessage.Kind.REPORT, code, s.get("title") + " — " + institution,
                        "The scheduled report " + s.get("title") + " is attached (" + export.fileName() + ").\n\nScheduled by " + s.get("owner_name")
                                + ". To change or stop it, open Reports › Scheduled in CIMS.\n\n" + institution + "\n(Sent by CIMS; please do not reply.)",
                        new EmailService.Attachment(export.fileName(), export.contentType(), export.content()));
            }
            status = "Sent to " + to.size() + " recipient(s) " + WHEN.format(ZonedDateTime.now(ZONE));
        } catch (Exception e) {
            status = "Not sent " + WHEN.format(ZonedDateTime.now(ZONE)) + ": " + (e instanceof AccessDeniedException ? "the owner may no longer run this report"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Scheduled report {} failed: {}", id, status);
        }
        if (status.length() > 300) status = status.substring(0, 300);
        OffsetDateTime next = advance ? nextRun((String) s.get("frequency"), (Integer) toInt(s.get("day_of_week")), (Integer) toInt(s.get("day_of_month")),
                ((Number) s.get("send_hour")).intValue(), ZonedDateTime.now(ZONE)) : null;
        if (next != null) {
            jdbc.update("update report_schedules set last_run_at = now(), last_status = ?, next_run_at = ? where id = ?", status, next, id);
        } else {
            jdbc.update("update report_schedules set last_run_at = now(), last_status = ? where id = ?", status, id);
        }
    }

    /** The next time after {@code now} the schedule falls due. */
    static OffsetDateTime nextRun(String frequency, Integer dayOfWeek, Integer dayOfMonth, int hour, ZonedDateTime now) {
        ZonedDateTime c = now.withMinute(0).withSecond(0).withNano(0).withHour(hour);
        switch (frequency) {
            case "DAILY" -> {
                if (!c.isAfter(now)) c = c.plusDays(1);
            }
            case "WEEKLY" -> {
                c = c.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
                if (!c.isAfter(now)) c = c.plusWeeks(1);
            }
            default -> {
                c = c.withDayOfMonth(dayOfMonth);
                if (!c.isAfter(now)) c = c.plusMonths(1).withDayOfMonth(dayOfMonth);
            }
        }
        return c.toOffsetDateTime();
    }

    @SuppressWarnings("unchecked")
    private Filter filter(Map<String, Object> raw) {
        try {
            return mapper.convertValue(raw == null ? Map.of() : raw, Filter.class);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_FILTERS", "The report filters are not valid");
        }
    }

    private ScheduleView get(Long id) {
        Long me = CurrentUser.id().orElse(-1L);
        return jdbc.query("select * from report_schedules where id = ?", (rs, i) -> view(rs, me), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Report schedule", id));
    }

    private void requireOwnerOrAdmin(Long id) {
        List<Long> owner = jdbc.queryForList("select owner_id from report_schedules where id = ?", Long.class, id);
        if (owner.isEmpty()) throw new NotFoundException("Report schedule", id);
        boolean admin = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().anyMatch(a -> a.getAuthority().equals("ROLE_INSTITUTION_ADMIN"));
        if (!owner.get(0).equals(CurrentUser.requireId()) && !admin) throw new AccessDeniedException("Only the person who set up the schedule can change it");
    }

    @SuppressWarnings("unchecked")
    private ScheduleView view(java.sql.ResultSet rs, Long me) throws java.sql.SQLException {
        Map<String, Object> filters;
        try {
            filters = mapper.readValue(rs.getString("filters"), Map.class);
        } catch (Exception e) {
            filters = Map.of();
        }
        return new ScheduleView(rs.getLong("id"), rs.getString("report_code"), rs.getString("title"), filters, rs.getString("format"),
                rs.getString("frequency"), (Integer) rs.getObject("day_of_week", Integer.class), (Integer) rs.getObject("day_of_month", Integer.class),
                rs.getInt("send_hour"), new ArrayList<>(List.of(rs.getString("recipients").split(","))), rs.getString("owner_name"),
                me != null && me == rs.getLong("owner_id"), rs.getBoolean("active"), rs.getObject("next_run_at", OffsetDateTime.class),
                rs.getObject("last_run_at", OffsetDateTime.class), rs.getString("last_status"));
    }

    private String json(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            throw new BusinessRuleException("INVALID_FILTERS", "The report filters are not valid");
        }
    }

    private static Integer dayOfWeek(ScheduleRequest r) {
        return "WEEKLY".equals(r.frequency()) ? r.dayOfWeek() : null;
    }

    private static Integer dayOfMonth(ScheduleRequest r) {
        return "MONTHLY".equals(r.frequency()) ? r.dayOfMonth() : null;
    }

    private static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
