package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.finance.reports.FinanceReportService;
import tz.co.msingi.cims.platform.security.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Role-based dashboards (FR-RPT-01): each section is computed only for users whose roles may see it. */
@Service
public class DashboardService {

    public record Point(String key, String label, long value) {
    }

    public record Money(String key, String label, BigDecimal amount, long count) {
    }

    public record ProgrammeRow(String code, String name, long value, long capacity, long accepted) {
    }

    public record Admissions(Long windowId, String windowName, String windowStatus, OffsetDateTime closesAt, List<Point> funnel,
                             List<Point> dailySubmissions, List<ProgrammeRow> programmes, long female, long male) {
    }

    public record Registry(long current, long registered, long awaitingRegistration, long female, long male, List<Point> byStatus,
                           List<ProgrammeRow> byProgramme, long pendingChangeRequests) {
    }

    public record Finance(Long academicYearId, String academicYear, BigDecimal billed, BigDecimal collected, BigDecimal outstanding,
                          Double collectionRate, BigDecimal collectedThisMonth, BigDecimal collectedToday, List<Money> dailyCollections,
                          List<Money> ageing, long daysNotReconciled, long openExceptions) {
    }

    public record Dashboard(Admissions admissions, Registry registry, Finance finance) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final FinanceReportService financeReports;

    private final tz.co.msingi.cims.platform.security.DataScope scope;

    public DashboardService(NamedParameterJdbcTemplate jdbc, FinanceReportService financeReports, tz.co.msingi.cims.platform.security.DataScope scope) {
        this.scope = scope;
        this.jdbc = jdbc;
        this.financeReports = financeReports;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Set<Role> roles = ReportService.currentRoles();
        return new Dashboard(any(roles, AdmissionReports.ROLES) ? admissions() : null, any(roles, RegistryReports.ROLES) ? registry() : null,
                any(roles, FinanceReports.ROLES) ? finance() : null);
    }

    private static boolean any(Set<Role> mine, Set<Role> allowed) {
        return mine.stream().anyMatch(allowed::contains);
    }

    // ------------------------------------------------------------------ admissions

    private Admissions admissions() {
        List<Map<String, Object>> w = jdbc.queryForList("""
                select id, name, status, closes_at from admission_windows
                order by case when status = 'PUBLISHED' then 0 else 1 end, opens_at desc limit 1""", Map.of());
        if (w.isEmpty()) return new Admissions(null, null, null, null, List.of(), List.of(), List.of(), 0, 0);
        Long id = ((Number) w.get(0).get("id")).longValue();
        MapSqlParameterSource p = new MapSqlParameterSource("w", id);
        Map<String, Object> f = jdbc.queryForMap("""
                select count(*) as started,
                       count(*) filter (where a.status not in ('DRAFT', 'PAYMENT_PENDING')) as submitted,
                       count(*) filter (where a.selected_window_programme_id is not null) as selected,
                       count(*) filter (where a.offer_issued_at is not null) as offered,
                       count(*) filter (where a.status = 'ACCEPTED') as accepted,
                       count(*) filter (where a.status not in ('DRAFT', 'PAYMENT_PENDING') and ap.sex = 'F') as female,
                       count(*) filter (where a.status not in ('DRAFT', 'PAYMENT_PENDING') and ap.sex = 'M') as male
                from applications a join applicants ap on ap.id = a.applicant_id where a.window_id = :w""", p);
        long handedOver = n(jdbc.queryForObject("select count(*) from students s join applications a on a.id = s.application_id where a.window_id = :w", p, Long.class));
        List<Point> funnel = List.of(new Point("started", "Applications started", n(f.get("started"))),
                new Point("submitted", "Submitted (fee paid)", n(f.get("submitted"))), new Point("selected", "Selected", n(f.get("selected"))),
                new Point("offered", "Verified and offered", n(f.get("offered"))), new Point("accepted", "Accepted", n(f.get("accepted"))),
                new Point("handedOver", "Handed over to Registry", handedOver));

        LocalDate today = LocalDate.now(Sql.ZONE);
        Map<LocalDate, Long> perDay = new HashMap<>();
        jdbc.query("""
                select cast(a.submitted_at at time zone 'Africa/Dar_es_Salaam' as date) as d, count(*) as c from applications a
                where a.window_id = :w and a.submitted_at >= :since group by 1""",
                new MapSqlParameterSource("w", id).addValue("since", today.minusDays(13).atStartOfDay(Sql.ZONE).toOffsetDateTime()),
                rs -> { perDay.put(rs.getObject("d", LocalDate.class), rs.getLong("c")); });
        List<Point> daily = new ArrayList<>();
        for (LocalDate d = today.minusDays(13); !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new Point(d.toString(), d.getDayOfMonth() + "/" + d.getMonthValue(), perDay.getOrDefault(d, 0L)));
        }

        List<ProgrammeRow> programmes = jdbc.query("""
                select p.code, p.name, wp.capacity,
                       (select count(*) from application_choices c join applications a on a.id = c.application_id
                        where c.window_programme_id = wp.id and c.choice_order = 1 and a.status not in ('DRAFT', 'PAYMENT_PENDING')) as first_choice,
                       (select count(*) from applications a where a.selected_window_programme_id = wp.id and a.status = 'ACCEPTED') as accepted
                from window_programmes wp join programmes p on p.id = wp.programme_id where wp.window_id = :w
                order by first_choice desc, p.name limit 8""", p,
                (rs, i) -> new ProgrammeRow(rs.getString("code"), rs.getString("name"), rs.getLong("first_choice"), rs.getLong("capacity"), rs.getLong("accepted")));
        return new Admissions(id, (String) w.get(0).get("name"), (String) w.get(0).get("status"),
                ((java.sql.Timestamp) w.get(0).get("closes_at")).toInstant().atOffset(java.time.ZoneOffset.UTC), funnel, daily, programmes,
                n(f.get("female")), n(f.get("male")));
    }

    // ------------------------------------------------------------------ registry

    private Registry registry() {
        // a Head of Department or Programme Coordinator sees the students of their own programmes
        var mine = scope.current();
        MapSqlParameterSource p = new MapSqlParameterSource("ids", mine.programmeIds().isEmpty() ? List.of(-1L) : mine.programmeIds());
        String in = mine.all() ? "" : " and s.programme_id in (:ids)";
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("select status, count(*) as c from students s where true" + in + " group by status", p,
                rs -> { byStatus.put(rs.getString("status"), rs.getLong("c")); });
        Map<String, Object> sex = jdbc.queryForMap("""
                select count(*) filter (where sex = 'F') as f, count(*) filter (where sex = 'M') as m
                from students s where status in ('ADMITTED', 'ACTIVE')\s""" + in, p);
        List<Point> statuses = new ArrayList<>();
        for (String st : List.of("ADMITTED", "ACTIVE", "POSTPONED", "SUSPENDED", "DISCONTINUED", "WITHDRAWN", "COMPLETED", "GRADUATED", "DECEASED")) {
            if (byStatus.containsKey(st)) statuses.add(new Point(st, AdmissionReports.label(st), byStatus.get(st)));
        }
        List<ProgrammeRow> programmes = jdbc.query("""
                select p.code, p.name, count(*) as c, count(*) filter (where s.status = 'ACTIVE') as registered
                from students s join programmes p on p.id = s.programme_id where s.status in ('ADMITTED', 'ACTIVE')""" + in + " group by p.code, p.name order by c desc, p.name limit 8", p,
                (rs, i) -> new ProgrammeRow(rs.getString("code"), rs.getString("name"), rs.getLong("c"), 0, rs.getLong("registered")));
        long pending = n(jdbc.queryForObject("select count(*) from student_change_requests r join students s on s.id = r.student_id where r.status = 'PENDING'"
                + in, p, Long.class));
        long admitted = byStatus.getOrDefault("ADMITTED", 0L), active = byStatus.getOrDefault("ACTIVE", 0L);
        return new Registry(admitted + active, active, admitted, n(sex.get("f")), n(sex.get("m")), statuses, programmes, pending);
    }

    // ------------------------------------------------------------------ finance

    private Finance finance() {
        List<Map<String, Object>> y = jdbc.queryForList("select id, code from academic_years order by is_current desc, start_date desc limit 1", Map.of());
        Long yearId = y.isEmpty() ? null : ((Number) y.get(0).get("id")).longValue();
        Map<String, Object> fees = jdbc.queryForMap("""
                select coalesce(sum(total_amount), 0) as billed, coalesce(sum(paid_amount), 0) as paid from invoices
                where purpose = 'ANNUAL_FEES' and status not in ('CANCELLED', 'CREDITED') and (cast(:y as bigint) is null or academic_year_id = :y)""",
                new MapSqlParameterSource("y", yearId));
        BigDecimal billed = (BigDecimal) fees.get("billed"), paid = (BigDecimal) fees.get("paid");
        LocalDate today = LocalDate.now(Sql.ZONE);
        Map<LocalDate, BigDecimal[]> perDay = new HashMap<>();
        jdbc.query("""
                select cast(paid_at at time zone 'Africa/Dar_es_Salaam' as date) as d, sum(amount) as a, count(*) as c from payments
                where paid_at >= :since and source <> 'INTERNAL' group by 1""",
                new MapSqlParameterSource("since", today.minusDays(29).atStartOfDay(Sql.ZONE).toOffsetDateTime()),
                rs -> { perDay.put(rs.getObject("d", LocalDate.class), new BigDecimal[]{rs.getBigDecimal("a"), BigDecimal.valueOf(rs.getLong("c"))}); });
        List<Money> daily = new ArrayList<>();
        for (LocalDate d = today.minusDays(29); !d.isAfter(today); d = d.plusDays(1)) {
            BigDecimal[] v = perDay.get(d);
            daily.add(new Money(d.toString(), d.getDayOfMonth() + "/" + d.getMonthValue(), v == null ? BigDecimal.ZERO : v[0], v == null ? 0 : v[1].longValue()));
        }
        BigDecimal month = n2(jdbc.queryForObject("select coalesce(sum(amount), 0) from payments where paid_at >= :since and source <> 'INTERNAL'",
                new MapSqlParameterSource("since", today.withDayOfMonth(1).atStartOfDay(Sql.ZONE).toOffsetDateTime()), BigDecimal.class));
        BigDecimal todays = daily.get(daily.size() - 1).amount();
        List<Money> ageing = financeReports.debtors(yearId).byBucket().stream()
                .map(a -> new Money(a.key(), a.label(), a.amount(), a.count())).toList();
        long notReconciled = n(jdbc.queryForObject("""
                select count(distinct cast(p.paid_at at time zone 'Africa/Dar_es_Salaam' as date)) from payments p
                where p.paid_at >= :since and p.paid_at < :today and p.source in ('GEPG', 'COLLECTION')
                  and not exists (select 1 from gepg_reconciliations r where r.recon_date = cast(p.paid_at at time zone 'Africa/Dar_es_Salaam' as date)
                                  and r.status = 'CLOSED')""",
                new MapSqlParameterSource("since", today.minusDays(30).atStartOfDay(Sql.ZONE).toOffsetDateTime())
                        .addValue("today", today.atStartOfDay(Sql.ZONE).toOffsetDateTime()), Long.class));
        long openExceptions = n(jdbc.queryForObject("select count(*) from gepg_reconciliation_items where status = 'OPEN'", Map.of(), Long.class));
        return new Finance(yearId, y.isEmpty() ? null : (String) y.get(0).get("code"), billed, paid, billed.subtract(paid),
                billed.signum() == 0 ? null : paid.doubleValue() / billed.doubleValue(), month, todays, daily, ageing, notReconciled, openExceptions);
    }

    private static long n(Object o) {
        return o instanceof Number x ? x.longValue() : 0;
    }

    private static BigDecimal n2(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
