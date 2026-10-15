package tz.co.msingi.cims.finance.muse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.muse.MuseClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Summarised fee postings to MUSE (FR-INT-07, FR-FIN-11): for a period, the cash received (GePG, collections and bank, not
 * internal transfers) debited to the institution's bank account and credited to revenue under each fee item's GFS code; money
 * held as credit goes under the default GFS code. Each period is posted once; a refused or failed posting can be sent again.
 */
@Service
@RequiredArgsConstructor
public class MuseService {

    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final MuseClient muse;
    private final IntegrationSettingsService settings;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public record Line(String account, String gfsCode, String description, BigDecimal debit, BigDecimal credit) {
    }

    public record Preview(LocalDate from, LocalDate to, String voteCode, List<Line> lines, BigDecimal total, List<String> problems) {
    }

    public record Posting(Long id, String batchRef, LocalDate from, LocalDate to, BigDecimal total, int lines, String status, String museRef,
                          String message, String postedByName, OffsetDateTime postedAt) {
    }

    @Transactional(readOnly = true)
    public Preview preview(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new BusinessRuleException("INVALID_RANGE", "The end date is before the start date");
        if (ChronoUnit.DAYS.between(from, to) > 92) throw new BusinessRuleException("RANGE_TOO_LONG", "Post at most three months at a time");
        if (!to.isBefore(LocalDate.now(EAT))) throw new BusinessRuleException("PERIOD_OPEN", "Post only days that have ended (up to yesterday)");
        var s = settings.get();
        String cash = s.museCashAccount() == null ? "BANK" : s.museCashAccount();
        var f = from.atStartOfDay(EAT).toOffsetDateTime();
        var t = to.plusDays(1).atStartOfDay(EAT).toOffsetDateTime();
        List<Line> credits = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        jdbc.query("""
                select coalesce(nullif(fi.gfs_code, ''), ?) as gfs, string_agg(distinct coalesce(fi.name, l.fee_item_code, 'Other'), ', ') as items,
                       sum(a.amount) as amount, bool_or(fi.gfs_code is null or fi.gfs_code = '') as defaulted
                from payment_allocations a join payments p on p.id = a.payment_id join invoice_lines l on l.id = a.invoice_line_id
                left join fee_items fi on upper(fi.code) = upper(l.fee_item_code)
                where p.source <> 'INTERNAL' and p.paid_at >= ? and p.paid_at < ? group by 1 order by 1 nulls first""", rs -> {
            String gfs = rs.getString(1);
            if (gfs == null) problems.add("No GFS code for: " + rs.getString(2) + ". Set the fee items' GFS codes, or a default GFS code under Integrations.");
            credits.add(new Line("REVENUE", gfs, "Fees received: " + cut(rs.getString(2), 150), BigDecimal.ZERO, rs.getBigDecimal(3)));
        }, s.gepgGfsCode(), f, t);
        BigDecimal credit = jdbc.queryForObject("select coalesce(sum(unallocated_amount), 0) from payments where source <> 'INTERNAL' and paid_at >= ? and paid_at < ?",
                BigDecimal.class, f, t);
        if (credit.signum() > 0) {
            if (s.gepgGfsCode() == null) problems.add("Money held as credit needs a default GFS code (Integrations).");
            credits.add(new Line("REVENUE", s.gepgGfsCode(), "Received in advance (held as credit)", BigDecimal.ZERO, credit));
        }
        BigDecimal total = credits.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Line> lines = new ArrayList<>();
        if (total.signum() > 0) lines.add(new Line(cash, null, "Fees collected " + from + " to " + to, total, BigDecimal.ZERO));
        lines.addAll(credits);
        if (total.signum() == 0) problems.add("No money was received in this period.");
        List<String> overlap = jdbc.queryForList("""
                select batch_ref from muse_postings where status = 'POSTED' and period_from <= ? and period_to >= ?""", String.class, to, from);
        if (!overlap.isEmpty()) problems.add("Part of this period is already posted (" + String.join(", ", overlap) + ").");
        return new Preview(from, to, s.museVoteCode(), lines, total, problems);
    }

    @Transactional
    public Posting post(LocalDate from, LocalDate to) {
        var s = settings.get();
        if (!s.museEnabled()) throw new BusinessRuleException("MUSE_OFF", "Posting to MUSE is switched off for this institution (Integrations).");
        if (s.museVoteCode() == null || s.museCashAccount() == null) {
            throw new BusinessRuleException("MUSE_NOT_SET_UP", "Enter the MUSE vote code and bank account under Integrations first.");
        }
        Preview p = preview(from, to);
        if (!p.problems().isEmpty()) throw new BusinessRuleException("CANNOT_POST", String.join(" ", p.problems()));
        Integer n = jdbc.queryForObject("select count(*) from muse_postings where period_from = ? and period_to = ?", Integer.class, from, to);
        String ref = "CIMS" + TenantContext.requireTenantId() + "-" + from.toString().replace("-", "") + "-" + to.toString().replace("-", "")
                + "-" + ((n == null ? 0 : n) + 1);
        MuseClient.PostResult r = muse.post(new MuseClient.Journal(s.museVoteCode(), ref, from, to, LocalDate.now(EAT), "TZS",
                p.lines().stream().map(l -> new MuseClient.Line(l.account(), l.gfsCode(), l.description(), l.debit(), l.credit())).toList()));
        String status = "DUPLICATE".equals(r.status()) ? "POSTED" : r.status();
        Long id = jdbc.queryForObject("""
                insert into muse_postings (tenant_id, batch_ref, period_from, period_to, lines, total, status, muse_ref, message, posted_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) returning id""", Long.class,
                ref, from, to, json(p.lines()), p.total(), status, r.museRef(), cut(r.message(), 500), CurrentUser.name());
        audit.record("MUSE_POSTED", "MusePosting", id, Map.of("from", from.toString(), "to", to.toString(), "total", p.total(), "status", status));
        return list().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Posting> list() {
        return jdbc.query("""
                select id, batch_ref, period_from, period_to, total, jsonb_array_length(lines), status, muse_ref, message, posted_by_name, posted_at
                from muse_postings order by period_from desc, id desc limit 100""",
                (rs, i) -> new Posting(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getBigDecimal(5),
                        rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class)));
    }

    @Transactional(readOnly = true)
    public List<Line> lines(Long id) {
        String s = jdbc.queryForObject("select lines::text from muse_postings where id = ?", String.class, id);
        try {
            return mapper.readValue(s, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
