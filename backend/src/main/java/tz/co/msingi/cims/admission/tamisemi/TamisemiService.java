package tz.co.msingi.cims.admission.tamisemi;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.integration.tamisemi.TamisemiClient;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.imports.ImportService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * TAMISEMI selections (FR-INT-08, BR-08): fetches the students TAMISEMI selected to the institution for a year and puts them
 * through the same checked import as a selections file (Data import → Selections): every row validated, a second person signs
 * off, then applicant accounts and SELECTED applications are created and each student is told by SMS.
 */
@Service
@RequiredArgsConstructor
public class TamisemiService {

    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private final TamisemiClient tamisemi;
    private final IntegrationSettingsService settings;
    private final ImportService imports;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record Fetch(Long id, int year, int selections, Long importBatchId, String batchNo, String summary, String doneByName, OffsetDateTime doneAt) {
    }

    @Transactional
    public Fetch fetch(Long windowId, Integer year) {
        String inst = settings.tamisemiCode();
        int y = year != null ? year : LocalDate.now(EAT).getYear();
        List<TamisemiClient.Selection> list = tamisemi.selections(inst, y);
        if (list.isEmpty()) throw new BusinessRuleException("NO_SELECTIONS", "TAMISEMI has no selections for " + inst + " in " + y + ".");
        StringBuilder csv = new StringBuilder(Csv.line("index_number", "exam_year", "first_name", "middle_name", "surname", "sex", "date_of_birth",
                "nationality", "phone", "programme_code", "reference"));
        for (TamisemiClient.Selection s : list) {
            csv.append(Csv.line(s.indexNumber(), s.examYear(), s.firstName(), s.middleName(), s.surname(), s.sex(), s.dateOfBirth(),
                    s.nationality() == null ? "Tanzanian" : s.nationality(), s.phone(), s.programmeCode(), s.reference()));
        }
        ImportService.BatchView b = imports.upload(ImportService.Kind.SELECTIONS, "TAMISEMI selections " + y + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8), windowId, "TAMISEMI");
        String summary = list.size() + " selection(s) fetched; " + b.errorRows() + " need attention before sign-off";
        Long id = jdbc.queryForObject("""
                insert into external_exchanges (tenant_id, system, kind, external_ref, sent, accepted, summary, import_batch_id, done_by_name)
                values (cims_current_tenant(), 'TAMISEMI', 'SELECTIONS', ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                inst + "/" + y, list.size(), list.size() - b.errorRows(), summary, b.id(), CurrentUser.name());
        audit.record("TAMISEMI_SELECTIONS_FETCHED", "ImportBatch", b.id(), Map.of("year", y, "selections", list.size()));
        return new Fetch(id, y, list.size(), b.id(), b.batchNo(), summary, CurrentUser.name(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<Fetch> history() {
        return jdbc.query("""
                select e.id, e.external_ref, e.sent, e.import_batch_id, b.batch_no, e.summary, e.done_by_name, e.done_at
                from external_exchanges e left join import_batches b on b.id = e.import_batch_id
                where e.system = 'TAMISEMI' order by e.done_at desc limit 20""",
                (rs, i) -> new Fetch(rs.getLong(1), year(rs.getString(2)), rs.getInt(3), rs.getObject(4, Long.class), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getObject(8, OffsetDateTime.class)));
    }

    private static int year(String ref) {
        try {
            return Integer.parseInt(ref.substring(ref.lastIndexOf('/') + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
