package tz.co.msingi.cims.finance.heslb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.RemittanceRequest;
import tz.co.msingi.cims.finance.sponsors.Sponsor;
import tz.co.msingi.cims.finance.sponsors.SponsorService;
import tz.co.msingi.cims.integration.heslb.HeslbClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HESLB exchange (FR-INT-05, BP-17): the Registrar sends registration (first year) and continuation (later years) data; the
 * bursary fetches HESLB's beneficiaries, which become HESLB sponsorships capped at what HESLB pays the institution, and HESLB's
 * disbursements, which the Accountant records against the HESLB sponsor invoice. Every exchange is kept with what came of it.
 */
@Service
@RequiredArgsConstructor
public class HeslbService {

    public static final String SPONSOR_CODE = "HESLB";
    private static final Set<String> REGISTRY = Set.of("INSTITUTION_ADMIN", "REGISTRAR");
    private static final Set<String> BURSARY = Set.of("INSTITUTION_ADMIN", "BURSAR", "ACCOUNTANT");

    private final JdbcTemplate jdbc;
    private final HeslbClient heslb;
    private final IntegrationSettingsService settings;
    private final SponsorService sponsors;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public record Problem(String key, String message) {
    }

    public record Exchange(Long id, String kind, String academicYear, String externalRef, int sent, int accepted, List<Problem> problems,
                           String summary, String doneByName, OffsetDateTime doneAt) {
    }

    public record DisbursementRow(Long id, String batchNo, LocalDate disbursedOn, BigDecimal amount, int students, String status,
                                  String invoiceNo, String recordedByName, OffsetDateTime recordedAt) {
    }

    public record SponsorInvoice(Long id, String invoiceNo, BigDecimal balance) {
    }

    public record Overview(boolean enabled, String institutionCode, Long academicYearId, String academicYear, long sponsored, BigDecimal sponsoredCap,
                           List<Exchange> exchanges, List<DisbursementRow> disbursements, List<SponsorInvoice> openInvoices) {
    }

    @Transactional(readOnly = true)
    public Overview overview(Long academicYearId) {
        require(union());
        var s = settings.get();
        String code = s.heslbCode() != null ? s.heslbCode() : settings.institutionCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        Map<String, Object> sp = jdbc.queryForMap("""
                select count(*) as n, coalesce(sum(x.cap_amount), 0) as cap from sponsorships x join sponsors s on s.id = x.sponsor_id
                where s.code = ? and x.academic_year_id = ? and x.status = 'ACTIVE'""", SPONSOR_CODE, yid);
        List<Exchange> ex = jdbc.query("""
                select e.id, e.kind, y.code, e.external_ref, e.sent, e.accepted, e.problems::text, e.summary, e.done_by_name, e.done_at
                from external_exchanges e left join academic_years y on y.id = e.academic_year_id
                where e.system = 'HESLB' order by e.done_at desc, e.id desc limit 30""",
                (rs, i) -> new Exchange(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        problems(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)));
        List<DisbursementRow> dis = jdbc.query("""
                select d.id, d.batch_no, d.disbursed_on, d.amount, d.students, d.status, i.invoice_no, d.recorded_by_name, d.recorded_at
                from heslb_disbursements d left join invoices i on i.id = d.invoice_id where d.academic_year_id = ? order by d.disbursed_on desc, d.id desc""",
                (rs, i) -> new DisbursementRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class)), yid);
        List<SponsorInvoice> inv = jdbc.query("""
                select i.id, i.invoice_no, i.total_amount - i.paid_amount from invoices i join sponsors s on s.id = i.payer_id
                where i.payer_type = 'SPONSOR' and s.code = ? and i.status in ('ISSUED', 'PARTIALLY_PAID') order by i.id""",
                (rs, i) -> new SponsorInvoice(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)), SPONSOR_CODE);
        return new Overview(s.heslbEnabled(), code, yid, (String) y.get("code"), ((Number) sp.get("n")).longValue(), (BigDecimal) sp.get("cap"), ex, dis, inv);
    }

    /**
     * Sends registration (students who entered this year) or continuation (students in later years) data: active students with
     * their CSEE index number, which is how HESLB knows them.
     */
    @Transactional
    public Exchange sendRegistrations(Long academicYearId, String kind) {
        require(REGISTRY);
        if (!Set.of("REGISTRATION", "CONTINUATION").contains(kind)) throw new BusinessRuleException("INVALID_KIND", "Choose REGISTRATION or CONTINUATION");
        String inst = settings.heslbCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        String entry = "REGISTRATION".equals(kind) ? "s.entry_academic_year_id = ?" : "s.entry_academic_year_id <> ?";
        List<HeslbClient.Student> list = jdbc.query("""
                select a.necta_index_number, s.registration_no, s.first_name, s.middle_name, s.surname, s.sex, p.code, p.name, s.year_of_study, s.status, s.phone
                from students s join programmes p on p.id = s.programme_id left join applicants a on a.id = s.applicant_id
                where s.status = 'ACTIVE' and\s""" + entry + " order by s.registration_no",
                (rs, i) -> new HeslbClient.Student(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11)), yid);
        if (list.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_SEND", "No active " + ("REGISTRATION".equals(kind) ? "first-year" : "continuing") + " students for " + y.get("code") + ".");
        }
        HeslbClient.SubmissionResult r = heslb.sendRegistrations(inst, (String) y.get("code"), kind, list);
        List<Problem> problems = r.rejected().stream().map(x -> new Problem(x.registrationNo(), x.reason())).toList();
        Long id = record(kind, yid, r.reference(), list.size(), r.accepted(), problems,
                r.accepted() + " of " + list.size() + " accepted by HESLB");
        audit.record("HESLB_" + kind + "_SENT", "ExternalExchange", id, Map.of("year", y.get("code"), "students", list.size(), "accepted", r.accepted()));
        return exchange(id);
    }

    /** HESLB's beneficiaries for the year become (or update) HESLB sponsorships, matched by CSEE index number, else registration number. */
    @Transactional
    public Exchange syncBeneficiaries(Long academicYearId) {
        require(BURSARY);
        String inst = settings.heslbCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<HeslbClient.Beneficiary> list = heslb.beneficiaries(inst, (String) y.get("code"));
        Long sponsorId = sponsors.ensureSponsor(SPONSOR_CODE, "Higher Education Students' Loans Board", Sponsor.Kind.LOAN_BOARD);
        Map<String, Long> byIndex = new HashMap<>();
        Map<String, Long> byReg = new HashMap<>();
        jdbc.query("select s.id, upper(a.necta_index_number), upper(s.registration_no) from students s left join applicants a on a.id = s.applicant_id", rs -> {
            if (rs.getString(2) != null) byIndex.put(rs.getString(2), rs.getLong(1));
            byReg.put(rs.getString(3), rs.getLong(1));
        });
        int created = 0, updated = 0, same = 0;
        List<Problem> problems = new ArrayList<>();
        for (HeslbClient.Beneficiary b : list) {
            Long sid = b.indexNumber() == null ? null : byIndex.get(b.indexNumber().toUpperCase());
            if (sid == null && b.registrationNo() != null) sid = byReg.get(b.registrationNo().toUpperCase());
            String key = b.indexNumber() != null ? b.indexNumber() : b.registrationNo();
            if (sid == null) {
                problems.add(new Problem(key, "No student with this index or registration number" + (b.fullName() == null ? "" : " (" + b.fullName() + ")")));
                continue;
            }
            if (b.toInstitution() == null || b.toInstitution().signum() <= 0) {
                problems.add(new Problem(key, "HESLB pays the institution nothing for this student"));
                continue;
            }
            try {
                switch (sponsors.fromExchange(sponsorId, sid, yid, b.toInstitution(), b.loanNumber())) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    default -> same++;
                }
            } catch (BusinessRuleException e) {
                problems.add(new Problem(key, e.getMessage()));
            }
        }
        Long id = record("BENEFICIARIES", yid, null, list.size(), created + updated + same, problems,
                list.size() + " beneficiaries: " + created + " new, " + updated + " updated, " + same + " unchanged");
        audit.record("HESLB_BENEFICIARIES_FETCHED", "ExternalExchange", id, Map.of("year", y.get("code"), "beneficiaries", list.size(), "created", created));
        return exchange(id);
    }

    /** HESLB's disbursements for the year not seen before are kept, waiting to be recorded against the HESLB invoice. */
    @Transactional
    public Exchange fetchDisbursements(Long academicYearId) {
        require(BURSARY);
        String inst = settings.heslbCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<HeslbClient.Disbursement> list = heslb.disbursements(inst, (String) y.get("code"));
        int added = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (HeslbClient.Disbursement d : list) {
            if (d.amount() == null || d.amount().signum() <= 0) continue;
            added += jdbc.update("""
                    insert into heslb_disbursements (tenant_id, academic_year_id, batch_no, disbursed_on, amount, students, lines)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::jsonb) on conflict (tenant_id, batch_no) do nothing""",
                    yid, d.batchNo(), d.disbursedOn(), d.amount(), d.students().size(), json(d.students()));
            total = total.add(d.amount());
        }
        Long id = record("DISBURSEMENTS", yid, null, list.size(), added, List.of(),
                list.size() + " disbursement(s) at HESLB, " + added + " new");
        audit.record("HESLB_DISBURSEMENTS_FETCHED", "ExternalExchange", id, Map.of("year", y.get("code"), "batches", list.size(), "new", added));
        return exchange(id);
    }

    /** Records a disbursement as HESLB's payment of a HESLB sponsor invoice; the students' shares move onto their invoices as usual. */
    @Transactional
    public DisbursementRow record(Long disbursementId, Long invoiceId) {
        require(BURSARY);
        Map<String, Object> d = jdbc.queryForList("select * from heslb_disbursements where id = ?", disbursementId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Disbursement", disbursementId));
        if ("RECORDED".equals(d.get("status"))) throw new BusinessRuleException("ALREADY_RECORDED", "Disbursement " + d.get("batch_no") + " is already recorded");
        Integer ok = jdbc.queryForObject("""
                select count(*) from invoices i join sponsors s on s.id = i.payer_id where i.id = ? and i.payer_type = 'SPONSOR' and s.code = ?""",
                Integer.class, invoiceId, SPONSOR_CODE);
        if (ok == null || ok == 0) throw new BusinessRuleException("NOT_HESLB_INVOICE", "Choose a HESLB sponsor invoice");
        sponsors.remittance(invoiceId, new RemittanceRequest((BigDecimal) d.get("amount"), (String) d.get("batch_no"),
                ((java.sql.Date) d.get("disbursed_on")).toLocalDate()));
        jdbc.update("update heslb_disbursements set status = 'RECORDED', invoice_id = ?, recorded_by_name = ?, recorded_at = now() where id = ?",
                invoiceId, CurrentUser.name(), disbursementId);
        audit.record("HESLB_DISBURSEMENT_RECORDED", "Invoice", invoiceId, Map.of("batch", d.get("batch_no"), "amount", d.get("amount")));
        return jdbc.queryForObject("""
                select d.id, d.batch_no, d.disbursed_on, d.amount, d.students, d.status, i.invoice_no, d.recorded_by_name, d.recorded_at
                from heslb_disbursements d left join invoices i on i.id = d.invoice_id where d.id = ?""",
                (rs, i) -> new DisbursementRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class)), disbursementId);
    }

    // ------------------------------------------------------------------ helpers

    private Long record(String kind, Long yearId, String ref, int sent, int accepted, List<Problem> problems, String summary) {
        return jdbc.queryForObject("""
                insert into external_exchanges (tenant_id, system, kind, academic_year_id, external_ref, sent, accepted, problems, summary, done_by_name)
                values (cims_current_tenant(), 'HESLB', ?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id""", Long.class,
                kind, yearId, ref, sent, accepted, json(problems), summary, CurrentUser.name());
    }

    private Exchange exchange(Long id) {
        return jdbc.queryForObject("""
                select e.id, e.kind, y.code, e.external_ref, e.sent, e.accepted, e.problems::text, e.summary, e.done_by_name, e.done_at
                from external_exchanges e left join academic_years y on y.id = e.academic_year_id where e.id = ?""",
                (rs, i) -> new Exchange(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        problems(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)), id);
    }

    /** The given academic year, or the current one. */
    private Map<String, Object> year(Long id) {
        List<Map<String, Object>> l = id != null ? jdbc.queryForList("select id, code from academic_years where id = ?", id)
                : jdbc.queryForList("select id, code from academic_years order by (current_date between start_date and end_date) desc, start_date desc limit 1");
        if (l.isEmpty()) throw new NotFoundException("Academic year", id);
        Map<String, Object> m = new HashMap<>(l.get(0));
        m.put("id", ((Number) m.get("id")).longValue());
        return m;
    }

    private List<Problem> problems(String s) {
        try {
            return s == null ? List.of() : List.of(mapper.readValue(s, Problem[].class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> union() {
        Set<String> s = new java.util.HashSet<>(REGISTRY);
        s.addAll(BURSARY);
        return s;
    }

    private static void require(Set<String> roles) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().anyMatch(a -> roles.contains(a.getAuthority().replaceFirst("^ROLE_", "")));
        if (!ok) throw new AccessDeniedException("Not permitted for your role");
    }
}
