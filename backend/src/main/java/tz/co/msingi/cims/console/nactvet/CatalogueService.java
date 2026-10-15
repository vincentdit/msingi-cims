package tz.co.msingi.cims.console.nactvet;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.CatalogueStatus;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.InstitutionDetail;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.InstitutionPage;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.InstitutionRow;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.ProgrammeView;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.Suggestion;
import tz.co.msingi.cims.console.nactvet.CatalogueDtos.SyncRun;
import tz.co.msingi.cims.integration.nactvet.NactvetCatalogueClient;
import tz.co.msingi.cims.integration.nactvet.NactvetCatalogueClient.Offering;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The NACTVET catalogue on the platform: pulled from NACTVET (as NOAS does), kept in platform tables and browsed by Msingi to
 * onboard institutions. A sync replaces what NACTVET no longer lists by marking it withdrawn (never deleting, so links to
 * onboarded institutions stay). A sync runs in the background; one at a time.
 */
@Slf4j
@Service
public class CatalogueService {

    private final NactvetCatalogueClient client;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformAuditService audit;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nactvet-catalogue-sync");
        t.setDaemon(true);
        return t;
    });

    public CatalogueService(NactvetCatalogueClient client, JdbcTemplate jdbc, TransactionTemplate tx, PlatformAuditService audit) {
        this.client = client;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public String mode() {
        return AopUtils.getTargetClass(client).getSimpleName().startsWith("Simulated") ? "SIMULATED" : "LIVE";
    }

    // ------------------------------------------------------------------ sync

    /** Starts a sync; with {@code wait} it returns when the sync has finished (tests, small catalogues). */
    public SyncRun sync(boolean wait) {
        Long actorId = CurrentUser.platformId().orElse(null);
        String actor = CurrentUser.platformName();
        Long id = tx.execute(s -> {
            // a sync left RUNNING by a stopped server for over two hours is closed as failed
            jdbc.update("update nactvet_catalogue_syncs set status = 'FAILED', finished_at = now(), error = 'Interrupted'"
                    + " where status = 'RUNNING' and started_at < now() - interval '2 hours'");
            Long running = jdbc.query("select id from nactvet_catalogue_syncs where status = 'RUNNING' limit 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (running != null) throw new BusinessRuleException("SYNC_RUNNING", "A sync from NACTVET is already running.");
            return jdbc.queryForObject("insert into nactvet_catalogue_syncs (status, mode, started_by) values ('RUNNING', ?, ?) returning id",
                    Long.class, mode(), actor);
        });
        Future<?> f = executor.submit(() -> run(id, actorId, actor));
        if (wait) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return run(id);
    }

    private void run(Long id, Long actorId, String actor) {
        OffsetDateTime start = jdbc.queryForObject("select started_at from nactvet_catalogue_syncs where id = ?", OffsetDateTime.class, id);
        try {
            List<Offering> offerings = client.fetchOfferings();
            Map<String, Object> result = tx.execute(s -> store(offerings, start));
            tx.executeWithoutResult(s -> {
                jdbc.update("update nactvet_catalogue_syncs set status = 'DONE', finished_at = now(), offerings = ?, institutions = ?, programmes = ?,"
                                + " new_institutions = ?, new_programmes = ?, withdrawn = ? where id = ?",
                        offerings.size(), result.get("institutions"), result.get("programmes"), result.get("newInstitutions"),
                        result.get("newProgrammes"), result.get("withdrawn"), id);
                Map<String, Object> d = new LinkedHashMap<>(result);
                d.put("offerings", offerings.size());
                d.put("mode", mode());
                audit.recordAs(actorId, actor, "NACTVET_CATALOGUE_SYNCED", null, "NactvetCatalogue", id, d);
            });
            log.info("NACTVET catalogue sync {} done: {}", id, result);
        } catch (RuntimeException e) {
            log.warn("NACTVET catalogue sync {} failed: {}", id, e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            tx.executeWithoutResult(s -> {
                jdbc.update("update nactvet_catalogue_syncs set status = 'FAILED', finished_at = now(), error = ? where id = ?",
                        msg.length() > 500 ? msg.substring(0, 500) : msg, id);
                audit.recordAs(actorId, actor, "NACTVET_CATALOGUE_SYNC_FAILED", null, "NactvetCatalogue", id, Map.of("error", msg));
            });
        }
    }

    /** Upserts institutions and programmes; whatever this sync did not see is marked withdrawn (only when NACTVET sent rows). */
    private Map<String, Object> store(List<Offering> offerings, OffsetDateTime start) {
        Map<String, Long> institutionIds = new HashMap<>();
        int newInstitutions = 0, newProgrammes = 0, programmes = 0;
        for (Offering o : offerings) {
            String instKey = firstNonBlank(o.institutionId(), o.institutionCode(), o.institutionName());
            if (instKey == null) continue;
            instKey = cut(instKey, 80);
            Long instId = institutionIds.get(instKey);
            if (instId == null) {
                Map<String, Object> r = jdbc.queryForMap("""
                        insert into nactvet_institutions (nactvet_key, nactvet_id, code, name, registration_number, region, district, ownership, mobile_number)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (nactvet_key) do update set nactvet_id = excluded.nactvet_id, code = excluded.code, name = excluded.name,
                            registration_number = excluded.registration_number, region = excluded.region, district = excluded.district,
                            ownership = excluded.ownership, mobile_number = excluded.mobile_number, active = true, last_seen_at = now()
                        returning id, (xmax = 0) as inserted""",
                        instKey, cut(o.institutionId(), 40), cut(o.institutionCode(), 64), cut(firstNonBlank(o.institutionName(), instKey), 200),
                        cut(o.registrationNumber(), 40), cut(o.region(), 60), cut(o.district(), 80), cut(o.ownership(), 40), cut(o.mobileNumber(), 30));
                instId = ((Number) r.get("id")).longValue();
                if (Boolean.TRUE.equals(r.get("inserted"))) newInstitutions++;
                institutionIds.put(instKey, instId);
            }
            String progKey = firstNonBlank(o.programmeId(), o.courseCode(), o.programmeName());
            if (progKey == null || o.programmeName() == null) continue;
            Integer level = ntaLevel(o.programmeLevel(), o.programmeName());
            Integer holder = switch (o.ntaEntryExamType() == null ? "" : o.ntaEntryExamType().trim()) {
                case "7" -> 4;
                case "8" -> 5;
                default -> null;
            };
            Boolean inserted = jdbc.queryForObject("""
                    insert into nactvet_programmes (institution_id, programme_key, nactvet_programme_id, course_code, name, programme_level, nta_level,
                        board_name, board_code, category_name, category_code, programme_fee, entry_requirement, selection_criteria, necta_requirement,
                        nta_requirement, nta_entry_min_gpa, nta_entry_holder_level, duration_years, male_capacity, female_capacity, total_capacity)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (institution_id, programme_key) do update set nactvet_programme_id = excluded.nactvet_programme_id,
                        course_code = excluded.course_code, name = excluded.name, programme_level = excluded.programme_level, nta_level = excluded.nta_level,
                        board_name = excluded.board_name, board_code = excluded.board_code, category_name = excluded.category_name,
                        category_code = excluded.category_code, programme_fee = excluded.programme_fee, entry_requirement = excluded.entry_requirement,
                        selection_criteria = excluded.selection_criteria, necta_requirement = excluded.necta_requirement,
                        nta_requirement = excluded.nta_requirement, nta_entry_min_gpa = excluded.nta_entry_min_gpa,
                        nta_entry_holder_level = excluded.nta_entry_holder_level, duration_years = excluded.duration_years,
                        male_capacity = excluded.male_capacity, female_capacity = excluded.female_capacity, total_capacity = excluded.total_capacity,
                        active = true, last_seen_at = now()
                    returning (xmax = 0)""", Boolean.class,
                    instId, cut(progKey, 80), cut(o.programmeId(), 40), cut(o.courseCode(), 30), cut(o.programmeName(), 200), cut(o.programmeLevel(), 20),
                    level, cut(o.boardName(), 150), cut(o.boardCode(), 30), cut(o.categoryName(), 150), cut(o.categoryCode(), 30),
                    cut(o.programmeFee(), 40), o.entryRequirement(), cut(o.selectionCriteria(), 1000), cut(o.specificNectaRequirement(), 1000),
                    cut(o.specificNtaRequirement(), 1000), gpa(o.ntaEntryMinGpa()), holder,
                    o.duration() == null || o.duration() < 1 || o.duration() > 8 ? null : o.duration(),
                    o.maleCapacity(), o.femaleCapacity(), o.totalCapacity());
            programmes++;
            if (Boolean.TRUE.equals(inserted)) newProgrammes++;
        }
        int withdrawn = 0;
        if (!offerings.isEmpty()) {
            withdrawn += jdbc.update("update nactvet_programmes set active = false where active and last_seen_at < ?", start);
            withdrawn += jdbc.update("update nactvet_institutions set active = false where active and last_seen_at < ?", start);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institutions", institutionIds.size());
        out.put("programmes", programmes);
        out.put("newInstitutions", newInstitutions);
        out.put("newProgrammes", newProgrammes);
        out.put("withdrawn", withdrawn);
        return out;
    }

    // ------------------------------------------------------------------ reading

    public CatalogueStatus status() {
        return tx.execute(s -> {
            SyncRun latest = jdbc.query("select * from nactvet_catalogue_syncs order by id desc limit 1", rs -> rs.next() ? syncRun(rs) : null);
            OffsetDateTime lastOk = jdbc.queryForObject("select max(finished_at) from nactvet_catalogue_syncs where status = 'DONE'", OffsetDateTime.class);
            Map<String, Object> c = jdbc.queryForMap("""
                    select (select count(*) from nactvet_institutions where active) as inst,
                           (select count(*) from nactvet_programmes where active) as prog,
                           (select count(*) from nactvet_institutions where tenant_id is not null) as onboarded""");
            List<String> regions = jdbc.queryForList("select distinct region from nactvet_institutions where region is not null and active order by region", String.class);
            List<String> ownerships = jdbc.queryForList("select distinct ownership from nactvet_institutions where ownership is not null and active order by ownership", String.class);
            return new CatalogueStatus(mode(), ((Number) c.get("inst")).longValue(), ((Number) c.get("prog")).longValue(),
                    ((Number) c.get("onboarded")).longValue(), lastOk, latest, regions, ownerships);
        });
    }

    public SyncRun run(Long id) {
        return tx.execute(s -> jdbc.query("select * from nactvet_catalogue_syncs where id = ?", rs -> rs.next() ? syncRun(rs) : null, id));
    }

    /** Institutions in the catalogue: search by name, code or registration number; filter by region, ownership and onboarding. */
    public InstitutionPage institutions(String q, String region, String ownership, String onboarded, boolean withdrawn, int page, int size) {
        int sz = Math.min(Math.max(size, 1), 200), p = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (!withdrawn) where.append(" and i.active");
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(i.name) like ? or lower(coalesce(i.code, '')) like ? or lower(coalesce(i.registration_number, '')) like ?)");
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            args.addAll(List.of(like, like, like));
        }
        if (region != null && !region.isBlank()) {
            where.append(" and i.region = ?");
            args.add(region);
        }
        if (ownership != null && !ownership.isBlank()) {
            where.append(" and i.ownership = ?");
            args.add(ownership);
        }
        if ("yes".equalsIgnoreCase(onboarded)) where.append(" and i.tenant_id is not null");
        if ("no".equalsIgnoreCase(onboarded)) where.append(" and i.tenant_id is null");
        return tx.execute(s -> {
            Long total = jdbc.queryForObject("select count(*) from nactvet_institutions i" + where, Long.class, args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(sz);
            pageArgs.add(p * sz);
            List<InstitutionRow> rows = jdbc.query(ROW_SQL + where + " order by i.name limit ? offset ?", (rs, i) -> row(rs), pageArgs.toArray());
            return new InstitutionPage(rows, total == null ? 0 : total, p, sz);
        });
    }

    public InstitutionDetail institution(Long id) {
        return tx.execute(s -> {
            InstitutionRow row = jdbc.query(ROW_SQL + " where i.id = ?", rs -> rs.next() ? row(rs) : null, id);
            if (row == null) throw new NotFoundException("NACTVET institution not found");
            List<ProgrammeView> programmes = programmes(id, row.tenantId());
            return new InstitutionDetail(row, suggestion(row), programmes);
        });
    }

    /** The institution's programmes on NACTVET; {@code imported} when the linked CIMS institution already has one with its NACTVET code. */
    List<ProgrammeView> programmes(Long institutionId, Long tenantId) {
        java.util.Set<String> imported = tenantId == null ? java.util.Set.of() : new java.util.HashSet<>(importedCodes(tenantId));
        return jdbc.query("select * from nactvet_programmes where institution_id = ? order by active desc, nta_level nulls last, name",
                (rs, i) -> new ProgrammeView(rs.getLong("id"), rs.getString("nactvet_programme_id"), rs.getString("course_code"), rs.getString("name"),
                        (Integer) rs.getObject("nta_level", Integer.class), joinNonBlank(rs.getString("board_name"), rs.getString("board_code")),
                        rs.getString("category_name"), rs.getString("programme_fee"), rs.getString("entry_requirement"), rs.getString("selection_criteria"),
                        rs.getString("necta_requirement"), rs.getString("nta_requirement"), rs.getBigDecimal("nta_entry_min_gpa"),
                        (Integer) rs.getObject("nta_entry_holder_level", Integer.class), (Integer) rs.getObject("duration_years", Integer.class),
                        (Integer) rs.getObject("male_capacity", Integer.class), (Integer) rs.getObject("female_capacity", Integer.class),
                        (Integer) rs.getObject("total_capacity", Integer.class), rs.getBoolean("active"),
                        imported.contains(ProgrammeImporter.nactvetCode(rs.getString("nactvet_programme_id"), rs.getString("programme_key")))),
                institutionId);
    }

    /** The NACTVET codes of a CIMS institution's programmes (read as that institution, since programmes are row-level secured). */
    private List<String> importedCodes(Long tenantId) {
        return tz.co.msingi.cims.platform.tenancy.TenantContext.callAs(new tz.co.msingi.cims.platform.tenancy.TenantContext.TenantInfo(tenantId, null),
                () -> new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()), requiresNew()).execute(s ->
                        jdbc.queryForList("select nactvet_code from programmes where nactvet_code is not null", String.class)));
    }

    private static org.springframework.transaction.support.DefaultTransactionDefinition requiresNew() {
        var d = new org.springframework.transaction.support.DefaultTransactionDefinition();
        d.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        d.setReadOnly(true);
        return d;
    }

    /** The NACTVET institution linked to a CIMS institution, if any. */
    public Long linkedTo(Long tenantId) {
        return tx.execute(s -> jdbc.query("select id from nactvet_institutions where tenant_id = ?", rs -> rs.next() ? rs.getLong(1) : null, tenantId));
    }

    // ------------------------------------------------------------------ helpers

    private static final String ROW_SQL = """
            select i.*, t.code as tenant_code,
                   (select count(*) from nactvet_programmes p where p.institution_id = i.id and p.active) as programmes,
                   (select min(nta_level) from nactvet_programmes p where p.institution_id = i.id and p.active) as min_level,
                   (select max(nta_level) from nactvet_programmes p where p.institution_id = i.id and p.active) as max_level
            from nactvet_institutions i left join tenants t on t.id = i.tenant_id""";

    private static InstitutionRow row(ResultSet rs) throws SQLException {
        return new InstitutionRow(rs.getLong("id"), rs.getString("nactvet_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("registration_number"), rs.getString("region"), rs.getString("district"), rs.getString("ownership"),
                rs.getString("mobile_number"), rs.getBoolean("active"), rs.getLong("programmes"), (Integer) rs.getObject("min_level", Integer.class),
                (Integer) rs.getObject("max_level", Integer.class), (Long) rs.getObject("tenant_id", Long.class), rs.getString("tenant_code"),
                rs.getObject("last_seen_at", OffsetDateTime.class));
    }

    private static SyncRun syncRun(ResultSet rs) throws SQLException {
        return new SyncRun(rs.getLong("id"), rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getString("status"), rs.getString("mode"), (Integer) rs.getObject("offerings", Integer.class),
                (Integer) rs.getObject("institutions", Integer.class), (Integer) rs.getObject("programmes", Integer.class),
                (Integer) rs.getObject("new_institutions", Integer.class), (Integer) rs.getObject("new_programmes", Integer.class),
                (Integer) rs.getObject("withdrawn", Integer.class), rs.getString("error"), rs.getString("started_by"));
    }

    /** What onboarding fills in: a CIMS code from NACTVET's institution code or the initials, and NACTVET's ownership category. */
    static Suggestion suggestion(InstitutionRow r) {
        String code = r.code() != null && r.code().matches("[A-Za-z][A-Za-z0-9]{1,19}") ? r.code().toUpperCase(Locale.ROOT) : initials(r.name());
        String ownership = tz.co.msingi.cims.platform.tenancy.Ownership.fromNactvet(r.ownership());
        String shortName = r.code() != null && r.code().length() <= 20 ? r.code().toUpperCase(Locale.ROOT) : code;
        return new Suggestion(code, titleCase(r.name()), shortName, r.registrationNumber(), ownership, r.mobileNumber());
    }

    static String initials(String name) {
        String s = Stream.of((name == null ? "" : name).split("[^A-Za-z]+"))
                .filter(w -> !w.isBlank() && !List.of("of", "and", "the", "for", "in").contains(w.toLowerCase(Locale.ROOT)))
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT)).collect(Collectors.joining());
        if (s.length() < 2) s = (s + "XX").substring(0, 2);
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    /** NACTVET sends some names in capitals; "KILIMO INSTITUTE OF …" reads "Kilimo Institute of …". */
    static String titleCase(String name) {
        if (name == null) return null;
        if (!name.equals(name.toUpperCase(Locale.ROOT))) return name.trim();
        List<String> small = List.of("of", "and", "the", "for", "in", "at", "on");
        StringBuilder out = new StringBuilder();
        String[] words = name.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (i > 0) out.append(' ');
            if (i > 0 && small.contains(w)) out.append(w);
            else if (w.length() <= 4 && w.matches("[a-z]+") && !w.matches(".*[aeiou].*")) out.append(w.toUpperCase(Locale.ROOT)); // "ict", "dit"…
            else out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** The NTA level from "NTA6"/"NTA Level 6", else from the award name. */
    static Integer ntaLevel(String programmeLevel, String name) {
        if (programmeLevel != null) {
            String digits = programmeLevel.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                int d = digits.charAt(0) - '0';
                if (d >= 1 && d <= 8) return d;
            }
        }
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (n.contains("BASIC TECHNICIAN CERTIFICATE")) return 4;
        if (n.contains("TECHNICIAN CERTIFICATE")) return 5;
        if (n.contains("ORDINARY DIPLOMA")) return 6;
        if (n.contains("HIGHER DIPLOMA")) return 7;
        if (n.contains("BACHELOR") || n.contains("DEGREE")) return 8;
        return null;
    }

    private static BigDecimal gpa(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            BigDecimal g = new BigDecimal(s.trim());
            return g.compareTo(BigDecimal.ZERO) < 0 || g.compareTo(BigDecimal.TEN) > 0 ? null : g.setScale(1, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null) return b;
        return b == null ? a : a + " (" + b + ")";
    }

    static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
