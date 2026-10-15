package tz.co.msingi.cims.platform.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.TabularFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Bringing data into CIMS from files (BR-39 / FR-ADM-S07, BR-08 / FR-ADM-15). A file is read and checked first, row by row, and
 * nothing changes until someone other than the uploader signs it off; it is checked again against the database at that moment
 * and applied only when every row is valid, all or nothing.
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    public enum Kind { STUDENTS, RESULTS, BALANCES, SELECTIONS }

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private static final Map<Kind, Set<String>> ROLES = Map.of(
            Kind.STUDENTS, Set.of("INSTITUTION_ADMIN", "REGISTRAR"),
            Kind.RESULTS, Set.of("INSTITUTION_ADMIN", "REGISTRAR", "EXAMINATIONS_OFFICER"),
            Kind.BALANCES, Set.of("INSTITUTION_ADMIN", "BURSAR", "ACCOUNTANT"),
            Kind.SELECTIONS, Set.of("INSTITUTION_ADMIN", "ADMISSIONS_OFFICER"));

    public static final List<String> EXTERNAL_BODIES = List.of("TAMISEMI", "MINISTRY_OF_HEALTH", "NACTVET_CAS", "SPONSOR", "OTHER");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final NumberSequenceService sequences;
    private final ImportValidator validator;
    private final ImportApplier applier;

    public record Problem(int row, String field, String message) {
    }

    public record BatchView(Long id, String batchNo, String kind, String fileName, String status, int totalRows, int errorRows,
                            List<Problem> errors, List<String> notes, Long windowId, String externalBody, String uploadedByName,
                            OffsetDateTime uploadedAt, String decidedByName, OffsetDateTime decidedAt, String signOffNote,
                            Map<String, Object> summary, List<Map<String, String>> preview) {
    }

    /** What a check of the file produced. */
    public record Checked(List<Map<String, String>> rows, List<Problem> errors, List<String> notes) {
    }

    @Transactional
    public BatchView upload(Kind kind, String fileName, byte[] data, Long windowId, String externalBody) {
        requireRole(kind);
        if (kind == Kind.SELECTIONS) {
            if (windowId == null) throw new BusinessRuleException("WINDOW_REQUIRED", "Choose the admission window the selected students join.");
            if (externalBody == null || !EXTERNAL_BODIES.contains(externalBody)) {
                throw new BusinessRuleException("BODY_REQUIRED", "Choose who selected the students: " + String.join(", ", EXTERNAL_BODIES));
            }
        }
        List<Map<String, String>> raw = TabularFile.read(data);
        if (raw.isEmpty()) throw new BusinessRuleException("EMPTY_FILE", "The file has a header row but no data.");
        Checked c = validator.check(kind, raw, windowId);
        int errorRows = (int) c.errors().stream().map(Problem::row).distinct().count();
        String no = "IMP-" + LocalDate.now(TZ).getYear() + "-" + NumberSequenceService.format("", sequences.next("IMPORT-BATCH"), 4);
        Long id = jdbc.queryForObject("""
                insert into import_batches (tenant_id, batch_no, kind, file_name, total_rows, error_rows, rows, errors, notes, window_id,
                  external_body, uploaded_by_id, uploaded_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?) returning id""", Long.class,
                no, kind.name(), clean(fileName), raw.size(), errorRows, json(raw), json(c.errors()), json(c.notes()), windowId,
                kind == Kind.SELECTIONS ? externalBody : null, CurrentUser.id().orElse(null), CurrentUser.name());
        audit.record("IMPORT_CHECKED", "ImportBatch", id, Map.of("kind", kind.name(), "rows", raw.size(), "errorRows", errorRows));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<BatchView> list(Kind kind) {
        List<Long> ids = jdbc.queryForList("select id from import_batches where (?::text is null or kind = ?) order by uploaded_at desc limit 100",
                Long.class, kind == null ? null : kind.name(), kind == null ? null : kind.name());
        return ids.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public BatchView get(Long id) {
        return view(id);
    }

    /** The second person signs the batch off; it is checked again now and applied whole, or not at all. */
    @Transactional
    public BatchView apply(Long id, String note) {
        Map<String, Object> b = row(id);
        Kind kind = Kind.valueOf((String) b.get("kind"));
        requireRole(kind);
        if (!"VALIDATED".equals(b.get("status"))) throw new BusinessRuleException("BATCH_CLOSED", "This file has already been applied or discarded.");
        Long uploader = b.get("uploaded_by_id") == null ? null : ((Number) b.get("uploaded_by_id")).longValue();
        if (uploader != null && uploader.equals(CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("SIGN_OFF_OWN", "Someone other than the person who uploaded the file must sign it off.");
        }
        List<Map<String, String>> rows = read((String) b.get("rows"));
        Long windowId = b.get("window_id") == null ? null : ((Number) b.get("window_id")).longValue();
        Checked again = validator.check(kind, rows, windowId);
        if (!again.errors().isEmpty()) {
            jdbc.update("update import_batches set errors = ?::jsonb, error_rows = ? where id = ?", json(again.errors()),
                    (int) again.errors().stream().map(Problem::row).distinct().count(), id);
            throw new BusinessRuleException("IMPORT_HAS_ERRORS", "The file has rows that are not valid now. Correct the file and upload it again.");
        }
        Map<String, Object> summary = applier.apply(kind, id, again.rows(), windowId, (String) b.get("external_body"));
        jdbc.update("""
                update import_batches set status = 'APPLIED', decided_by_name = ?, decided_at = now(), sign_off_note = ?, summary = ?::jsonb where id = ?""",
                CurrentUser.name(), note == null || note.isBlank() ? null : note.trim(), json(summary), id);
        audit.record("IMPORT_APPLIED", "ImportBatch", id, Map.of("kind", kind.name(), "summary", summary));
        return get(id);
    }

    @Transactional
    public BatchView discard(Long id) {
        Map<String, Object> b = row(id);
        requireRole(Kind.valueOf((String) b.get("kind")));
        if (!"VALIDATED".equals(b.get("status"))) throw new BusinessRuleException("BATCH_CLOSED", "This file has already been applied or discarded.");
        jdbc.update("update import_batches set status = 'DISCARDED', decided_by_name = ?, decided_at = now() where id = ?", CurrentUser.name(), id);
        audit.record("IMPORT_DISCARDED", "ImportBatch", id, Map.of());
        return get(id);
    }

    /** The rows with their problems, as a CSV to correct the file from. */
    @Transactional(readOnly = true)
    public String errorReport(Long id) {
        BatchView v = view(id);
        StringBuilder sb = new StringBuilder(Csv.line("Row", "Column", "Problem")).append("\n");
        v.errors().forEach(p -> sb.append(Csv.line(p.row(), p.field(), p.message())).append("\n"));
        return sb.toString();
    }

    public static String template(Kind kind) {
        List<String> cols = ImportValidator.COLUMNS.get(kind);
        List<String> example = ImportValidator.EXAMPLES.get(kind);
        return Csv.line(cols.toArray()) + "\n" + Csv.line(example.toArray()) + "\n";
    }

    // ----------------------------------------------------------------

    private void requireRole(Kind kind) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> ROLES.get(kind).contains(a.replaceFirst("^ROLE_", "")));
        if (!ok) throw new org.springframework.security.access.AccessDeniedException("Not allowed to import " + kind.name().toLowerCase());
    }

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("select kind, status, uploaded_by_id, rows::text as rows, window_id, external_body from import_batches where id = ?", id);
        if (r.isEmpty()) throw new NotFoundException("Import", id);
        return r.get(0);
    }

    private BatchView view(Long id) {
        List<BatchView> r = jdbc.query("""
                select id, batch_no, kind, file_name, status, total_rows, error_rows, errors::text, notes::text, window_id, external_body,
                       uploaded_by_name, uploaded_at, decided_by_name, decided_at, sign_off_note, summary::text, rows::text
                from import_batches where id = ?""", (rs, i) -> {
            List<Map<String, String>> rows = read(rs.getString(18));
            return new BatchView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                    parse(rs.getString(8), new TypeReference<List<Problem>>() {}), parse(rs.getString(9), new TypeReference<List<String>>() {}),
                    (Long) rs.getObject(10), rs.getString(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class), rs.getString(14),
                    rs.getObject(15, OffsetDateTime.class), rs.getString(16),
                    rs.getString(17) == null ? null : parse(rs.getString(17), new TypeReference<Map<String, Object>>() {}),
                    rows.subList(0, Math.min(rows.size(), 20)));
        }, id);
        if (r.isEmpty()) throw new NotFoundException("Import", id);
        return r.get(0);
    }

    private List<Map<String, String>> read(String s) {
        return parse(s, new TypeReference<List<Map<String, String>>>() {});
    }

    private <T> T parse(String s, TypeReference<T> t) {
        try {
            return mapper.readValue(s, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clean(String name) {
        String n = name == null ? "upload" : name.replaceAll(".*[\\\\/]", "").replaceAll("[^A-Za-z0-9 ._()-]", "");
        return n.isBlank() ? "upload" : n.length() > 200 ? n.substring(0, 200) : n;
    }

    static BigDecimal decimal(String s) {
        return s == null ? null : new BigDecimal(s.replace(",", "").trim());
    }
}
