package tz.co.msingi.cims.registry.nactvet;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.nactvet.NactvetSubmissionClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NACTVET student registration (FR-REG-07, BR-07): the Registrar sends the year's newly registered students (active, entered
 * this academic year, not yet registered with NACTVET or queried before) as one batch; NACTVET's outcome for each student is
 * fetched and kept, and queried students can be corrected and sent again in a later batch.
 */
@Service
@RequiredArgsConstructor
public class NactvetRegistrationService {

    private final JdbcTemplate jdbc;
    private final NactvetSubmissionClient nactvet;
    private final IntegrationSettingsService settings;
    private final AuditService audit;

    public record Batch(Long id, String academicYear, String batchRef, String status, int students, int registered, int queried,
                        String submittedByName, OffsetDateTime submittedAt, OffsetDateTime checkedAt) {
    }

    public record Item(Long studentId, String registrationNo, String name, String programmeCode, String status, String reason) {
    }

    public record Overview(Long academicYearId, String academicYear, int waiting, int withoutVerification, List<Batch> batches) {
    }

    private static final String WAITING = """
            from students s join programmes p on p.id = s.programme_id left join applicants a on a.id = s.applicant_id
            where s.status = 'ACTIVE' and s.entry_academic_year_id = ? and (s.nactvet_reg_status is null or s.nactvet_reg_status = 'QUERIED')""";

    @Transactional(readOnly = true)
    public Overview overview(Long academicYearId) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        Integer waiting = jdbc.queryForObject("select count(*) " + WAITING, Integer.class, yid);
        Integer noVerif = jdbc.queryForObject("select count(*) " + WAITING + " and s.nactvet_verification_no is null", Integer.class, yid);
        return new Overview(yid, (String) y.get("code"), waiting == null ? 0 : waiting, noVerif == null ? 0 : noVerif, batches());
    }

    @Transactional
    public Batch submit(Long academicYearId) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Object[]> ids = new java.util.ArrayList<>();
        List<NactvetSubmissionClient.RegStudent> list = jdbc.query("""
                select s.id, s.registration_no, s.nactvet_verification_no, a.necta_index_number, s.first_name, s.middle_name, s.surname, s.sex,
                       s.date_of_birth, s.nationality, s.phone, coalesce(p.nactvet_code, p.code), s.year_of_study\s""" + WAITING + " order by s.registration_no",
                (rs, i) -> {
                    ids.add(new Object[]{rs.getLong(1), rs.getString(2)});
                    return new NactvetSubmissionClient.RegStudent(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getInt(13));
                }, yid);
        if (list.isEmpty()) throw new BusinessRuleException("NOTHING_TO_SEND", "Every active student who entered in " + y.get("code") + " is already registered with NACTVET or waiting for its answer.");
        String ref = nactvet.submitRegistrations(settings.institutionCode(), (String) y.get("code"), list);
        Long batchId = jdbc.queryForObject("""
                insert into nactvet_registration_batches (tenant_id, academic_year_id, batch_ref, students, submitted_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?) returning id""", Long.class, yid, ref, list.size(), CurrentUser.name());
        for (Object[] s : ids) {
            jdbc.update("insert into nactvet_registration_items (tenant_id, batch_id, student_id) values (cims_current_tenant(), ?, ?)", batchId, s[0]);
            jdbc.update("update students set nactvet_reg_status = 'SUBMITTED' where id = ?", s[0]);
        }
        audit.record("NACTVET_REGISTRATION_SENT", "NactvetRegistrationBatch", batchId, Map.of("year", y.get("code"), "students", list.size(), "batch", ref));
        return refresh(batchId);
    }

    /** NACTVET's outcome so far: registered and queried students are recorded; the batch is complete once every student is decided. */
    @Transactional
    public Batch refresh(Long batchId) {
        Batch b = batch(batchId);
        NactvetSubmissionClient.Status st = nactvet.registrationStatus(b.batchRef());
        Map<String, Long> byReg = new HashMap<>();
        jdbc.query("select s.registration_no, s.id from nactvet_registration_items i join students s on s.id = i.student_id where i.batch_id = ?",
                rs -> {
                    byReg.put(rs.getString(1).toUpperCase(), rs.getLong(2));
                }, batchId);
        for (NactvetSubmissionClient.ItemStatus i : st.items()) {
            Long sid = i.registrationNo() == null ? null : byReg.get(i.registrationNo().toUpperCase());
            if (sid == null || !List.of("REGISTERED", "QUERIED").contains(i.status())) continue;
            jdbc.update("""
                    update nactvet_registration_items set status = ?, reason = ?, decided_at = coalesce(decided_at, now())
                    where batch_id = ? and student_id = ? and status = 'SUBMITTED'""", i.status(), cut(i.reason()), batchId, sid);
            jdbc.update("update students set nactvet_reg_status = ? where id = ? and nactvet_reg_status = 'SUBMITTED'", i.status(), sid);
        }
        jdbc.update("""
                update nactvet_registration_batches b set checked_at = now(),
                  registered = (select count(*) from nactvet_registration_items i where i.batch_id = b.id and i.status = 'REGISTERED'),
                  queried = (select count(*) from nactvet_registration_items i where i.batch_id = b.id and i.status = 'QUERIED'),
                  status = case when exists (select 1 from nactvet_registration_items i where i.batch_id = b.id and i.status = 'SUBMITTED')
                                then 'SUBMITTED' else 'COMPLETED' end
                where id = ?""", batchId);
        Batch after = batch(batchId);
        if ("COMPLETED".equals(after.status()) && !"COMPLETED".equals(b.status())) {
            audit.record("NACTVET_REGISTRATION_OUTCOMES", "NactvetRegistrationBatch", batchId,
                    Map.of("registered", after.registered(), "queried", after.queried()));
        }
        return after;
    }

    @Transactional(readOnly = true)
    public List<Item> items(Long batchId) {
        batch(batchId);
        return jdbc.query("""
                select s.id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), p.code, i.status, i.reason
                from nactvet_registration_items i join students s on s.id = i.student_id join programmes p on p.id = s.programme_id
                where i.batch_id = ? order by i.status desc, s.registration_no""",
                (rs, n) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), batchId);
    }

    private List<Batch> batches() {
        return jdbc.query(SELECT + " order by b.submitted_at desc, b.id desc limit 30", (rs, i) -> row(rs));
    }

    private Batch batch(Long id) {
        List<Batch> l = jdbc.query(SELECT + " where b.id = ?", (rs, i) -> row(rs), id);
        if (l.isEmpty()) throw new NotFoundException("NACTVET registration batch", id);
        return l.get(0);
    }

    private static final String SELECT = """
            select b.id, y.code, b.batch_ref, b.status, b.students, b.registered, b.queried, b.submitted_by_name, b.submitted_at, b.checked_at
            from nactvet_registration_batches b join academic_years y on y.id = b.academic_year_id""";

    private static Batch row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Batch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class));
    }

    private Map<String, Object> year(Long id) {
        List<Map<String, Object>> l = id != null ? jdbc.queryForList("select id, code from academic_years where id = ?", id)
                : jdbc.queryForList("select id, code from academic_years order by (current_date between start_date and end_date) desc, start_date desc limit 1");
        if (l.isEmpty()) throw new NotFoundException("Academic year", id);
        Map<String, Object> m = new HashMap<>(l.get(0));
        m.put("id", ((Number) m.get("id")).longValue());
        return m;
    }

    private static String cut(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }
}
