package tz.co.msingi.cims.assets;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The asset register (FR-AST-01..03): every item of furniture, equipment, vehicle and building with its tag, where it is, who looks
 * after it, its cost and book value (straight-line over its useful life), its condition and status, and the history of each change;
 * and verification exercises, in which every asset is looked for and the missing ones listed.
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    static final Set<String> CATEGORIES = Set.of("FURNITURE", "ICT", "LAB_EQUIPMENT", "WORKSHOP", "VEHICLE", "BUILDING", "LAND", "OFFICE_EQUIPMENT", "OTHER");
    static final Set<String> CONDITIONS = Set.of("NEW", "GOOD", "FAIR", "POOR", "UNSERVICEABLE");
    static final Set<String> STATUSES = Set.of("IN_USE", "IN_STORE", "UNDER_REPAIR", "LOST");
    static final Set<String> FUNDING = Set.of("GOVERNMENT", "OWN", "DONOR", "OTHER");
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final NumberSequenceService sequences;
    private final AuditService audit;

    public record AssetRequest(String tagNo, String name, String category, String description, String serialNo, Long campusId, Long venueId, String location,
                               Long departmentId, String custodianName, LocalDate acquiredOn, BigDecimal cost, Integer usefulLifeYears, String funding,
                               String supplier, String condition, String status) {
    }

    public record AssetView(Long id, String tagNo, String name, String category, String description, String serialNo, Long campusId, String campus,
                            Long venueId, String venue, String location, Long departmentId, String department, String custodianName, LocalDate acquiredOn,
                            BigDecimal cost, Integer usefulLifeYears, BigDecimal bookValue, String funding, String supplier, String condition, String status,
                            LocalDate disposedOn, String disposalNote, OffsetDateTime lastVerifiedAt) {
    }

    public record Event(String kind, String details, String byName, OffsetDateTime at) {
    }

    public record AssetDetail(AssetView asset, List<Event> history) {
    }

    public record MoveRequest(Long campusId, Long venueId, String location, Long departmentId, String custodianName, String note) {
    }

    public record ConditionRequest(String condition, String status, String note) {
    }

    public record DisposeRequest(LocalDate disposedOn, String note) {
    }

    public record Overview(int assets, BigDecimal cost, BigDecimal bookValue, int poorOrWorse, int underRepair, int lost, int notVerifiedThisYear,
                           List<Map<String, Object>> byCategory) {
    }

    public record VerificationView(Long id, String name, String campus, String startedByName, OffsetDateTime startedAt, String closedByName,
                                   OffsetDateTime closedAt, int total, int found, int missing, int unchecked) {
    }

    public record VerificationLine(Long id, Long assetId, String tagNo, String name, String location, String department, String expectedCondition,
                                   Boolean found, String condition, String note, String checkedByName, OffsetDateTime checkedAt) {
    }

    public record VerificationDetail(VerificationView verification, List<VerificationLine> lines) {
    }

    public record CheckRequest(Boolean found, String condition, String note) {
    }

    // ------------------------------------------------------------------ register

    @Transactional(readOnly = true)
    public List<AssetView> list(String q, String category, Long campusId, Long departmentId, String status) {
        StringBuilder sql = new StringBuilder(BASE + " where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status == null || status.isBlank()) sql.append(" and a.status <> 'DISPOSED'");
        else if (!"ALL".equalsIgnoreCase(status)) {
            sql.append(" and a.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and a.category = ?");
            args.add(category.toUpperCase(Locale.ROOT));
        }
        if (campusId != null) {
            sql.append(" and a.campus_id = ?");
            args.add(campusId);
        }
        if (departmentId != null) {
            sql.append(" and a.department_id = ?");
            args.add(departmentId);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(a.tag_no) like ? or lower(a.name) like ? or lower(coalesce(a.serial_no, '')) like ? or lower(coalesce(a.custodian_name, '')) like ?"
                    + " or lower(coalesce(a.location, v.name, '')) like ?)");
            for (int i = 0; i < 5; i++) args.add(like);
        }
        sql.append(" order by a.tag_no limit 2000");
        return jdbc.query(sql.toString(), (rs, n) -> view(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public AssetDetail get(Long id) {
        AssetView a = jdbc.query(BASE + " where a.id = ?", (rs, n) -> view(rs), id).stream().findFirst().orElseThrow(() -> new NotFoundException("Asset not found"));
        return new AssetDetail(a, jdbc.query("select kind, details, by_name, at from asset_events where asset_id = ? order by at desc, id desc",
                (rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3), odt(rs.getTimestamp(4))), id));
    }

    @Transactional(readOnly = true)
    public AssetDetail byTag(String tag) {
        Long id = jdbc.query("select id from assets where lower(tag_no) = lower(?)", (rs, n) -> rs.getLong(1), tag == null ? "" : tag.trim()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No asset has the tag " + tag));
        return get(id);
    }

    @Transactional
    public AssetDetail add(AssetRequest r) {
        String tag = r.tagNo() == null || r.tagNo().isBlank()
                ? NumberSequenceService.format(TenantContext.require().code() + "/AST/", sequences.next("ASSET"), 5)
                : r.tagNo().trim();
        if (!tag.matches("[A-Za-z0-9/._-]{1,40}")) throw new BusinessRuleException("TAG_INVALID", "Give the tag (letters, digits, / . _ -; at most 40)");
        if (exists("select count(*) from assets where lower(tag_no) = lower(?)", tag)) throw new BusinessRuleException("TAG_TAKEN", "Another asset has the tag " + tag);
        validate(r);
        String status = r.status() == null || r.status().isBlank() ? "IN_USE" : oneOf(r.status(), STATUSES, "the status");
        Long id = jdbc.queryForObject("""
                insert into assets (tenant_id, tag_no, name, category, description, serial_no, campus_id, venue_id, location, department_id, custodian_name,
                  acquired_on, cost, useful_life_years, funding, supplier, condition, status, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                tag, required(r.name(), 200, "the name"), oneOf(r.category(), CATEGORIES, "the category"), cut(r.description(), 500), cut(r.serialNo(), 80),
                r.campusId(), r.venueId(), cut(r.location(), 150), r.departmentId(), cut(r.custodianName(), 150), r.acquiredOn(), r.cost(), r.usefulLifeYears(),
                r.funding() == null || r.funding().isBlank() ? null : oneOf(r.funding(), FUNDING, "the source of funds"), cut(r.supplier(), 150),
                r.condition() == null || r.condition().isBlank() ? "GOOD" : oneOf(r.condition(), CONDITIONS, "the condition"), status, CurrentUser.name());
        event(id, "ADDED", "Added to the register" + (r.cost() == null ? "" : ", cost TZS " + money(r.cost())));
        audit.record("ASSET_ADDED", "Asset", id, Map.of("tag", tag));
        return get(id);
    }

    /** Changes the details that are not a move or a condition (name, serial number, cost, useful life, funding, supplier). */
    @Transactional
    public AssetDetail update(Long id, AssetRequest r) {
        AssetView a = get(id).asset();
        if ("DISPOSED".equals(a.status())) throw new BusinessRuleException("ASSET_DISPOSED", "The asset has been disposed of");
        validate(r);
        jdbc.update("""
                update assets set name = ?, category = ?, description = ?, serial_no = ?, acquired_on = ?, cost = ?, useful_life_years = ?, funding = ?,
                  supplier = ?, updated_at = now() where id = ?""", required(r.name(), 200, "the name"), oneOf(r.category(), CATEGORIES, "the category"),
                cut(r.description(), 500), cut(r.serialNo(), 80), r.acquiredOn(), r.cost(), r.usefulLifeYears(),
                r.funding() == null || r.funding().isBlank() ? null : oneOf(r.funding(), FUNDING, "the source of funds"), cut(r.supplier(), 150), id);
        audit.record("ASSET_CHANGED", "Asset", id, Map.of("tag", a.tagNo()));
        return get(id);
    }

    @Transactional
    public AssetDetail move(Long id, MoveRequest r) {
        AssetView a = get(id).asset();
        if ("DISPOSED".equals(a.status())) throw new BusinessRuleException("ASSET_DISPOSED", "The asset has been disposed of");
        String to = place(r.campusId(), r.venueId(), r.location(), r.departmentId());
        String from = place(a.campusId(), a.venueId(), a.location(), a.departmentId());
        String custodian = cut(r.custodianName(), 150);
        if (to.equals(from) && Objects.equals(custodian, a.custodianName())) throw new BusinessRuleException("NOTHING_CHANGED", "Nothing changed");
        jdbc.update("update assets set campus_id = ?, venue_id = ?, location = ?, department_id = ?, custodian_name = ?, updated_at = now() where id = ?",
                r.campusId(), r.venueId(), cut(r.location(), 150), r.departmentId(), custodian, id);
        StringBuilder d = new StringBuilder();
        if (!to.equals(from)) d.append("Moved from ").append(from.isEmpty() ? "(no place)" : from).append(" to ").append(to.isEmpty() ? "(no place)" : to);
        if (!Objects.equals(custodian, a.custodianName())) {
            if (!d.isEmpty()) d.append("; ");
            d.append("now looked after by ").append(custodian == null ? "no one named" : custodian);
        }
        if (r.note() != null && !r.note().isBlank()) d.append(" (").append(r.note().trim()).append(")");
        event(id, "MOVED", d.toString());
        audit.record("ASSET_MOVED", "Asset", id, Map.of("tag", a.tagNo(), "to", to));
        return get(id);
    }

    @Transactional
    public AssetDetail setCondition(Long id, ConditionRequest r) {
        AssetView a = get(id).asset();
        if ("DISPOSED".equals(a.status())) throw new BusinessRuleException("ASSET_DISPOSED", "The asset has been disposed of");
        String condition = r.condition() == null || r.condition().isBlank() ? a.condition() : oneOf(r.condition(), CONDITIONS, "the condition");
        String status = r.status() == null || r.status().isBlank() ? a.status() : oneOf(r.status(), STATUSES, "the status");
        if (condition.equals(a.condition()) && status.equals(a.status())) throw new BusinessRuleException("NOTHING_CHANGED", "Nothing changed");
        jdbc.update("update assets set condition = ?, status = ?, updated_at = now() where id = ?", condition, status, id);
        String note = r.note() == null || r.note().isBlank() ? "" : " (" + r.note().trim() + ")";
        if (!condition.equals(a.condition())) event(id, "CONDITION", "Condition " + label(a.condition()) + " → " + label(condition) + note);
        if (!status.equals(a.status())) event(id, "STATUS", label(a.status()) + " → " + label(status) + note);
        audit.record("ASSET_CONDITION", "Asset", id, Map.of("condition", condition, "status", status));
        return get(id);
    }

    @Transactional
    public AssetDetail dispose(Long id, DisposeRequest r) {
        AssetView a = get(id).asset();
        if ("DISPOSED".equals(a.status())) throw new BusinessRuleException("ASSET_DISPOSED", "The asset has already been disposed of");
        String note = required(r.note(), 300, "how it was disposed of and the approval (board minute, auction or the like)");
        LocalDate on = r.disposedOn() == null ? LocalDate.now(TZ) : r.disposedOn();
        jdbc.update("update assets set status = 'DISPOSED', disposed_on = ?, disposal_note = ?, updated_at = now() where id = ?", on, note, id);
        event(id, "DISPOSED", "Disposed of on " + on + ": " + note);
        audit.record("ASSET_DISPOSED", "Asset", id, Map.of("tag", a.tagNo(), "on", on.toString()));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        List<AssetView> all = list(null, null, null, null, null);
        int year = LocalDate.now(TZ).getYear();
        BigDecimal cost = all.stream().map(AssetView::cost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal book = all.stream().map(AssetView::bookValue).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> byCategory = jdbc.queryForList("""
                select category, count(*) as assets, coalesce(sum(cost), 0) as cost from assets where status <> 'DISPOSED' group by category order by 2 desc""");
        return new Overview(all.size(), cost, book, (int) all.stream().filter(a -> Set.of("POOR", "UNSERVICEABLE").contains(a.condition())).count(),
                (int) all.stream().filter(a -> "UNDER_REPAIR".equals(a.status())).count(), (int) all.stream().filter(a -> "LOST".equals(a.status())).count(),
                (int) all.stream().filter(a -> a.lastVerifiedAt() == null || a.lastVerifiedAt().atZoneSameInstant(TZ).getYear() < year).count(), byCategory);
    }

    @Transactional
    public byte[] register(String status) {
        List<AssetView> all = list(null, null, null, null, status);
        List<List<Object>> rows = new ArrayList<>();
        for (AssetView a : all) {
            List<Object> r = new ArrayList<>();
            r.add(a.tagNo());
            r.add(a.name());
            r.add(label(a.category()));
            r.add(a.serialNo());
            r.add(a.campus());
            r.add(a.venue() != null ? a.venue() : a.location());
            r.add(a.department());
            r.add(a.custodianName());
            r.add(a.acquiredOn());
            r.add(a.cost());
            r.add(a.usefulLifeYears());
            r.add(a.bookValue());
            r.add(a.funding() == null ? null : label(a.funding()));
            r.add(label(a.condition()));
            r.add(label(a.status()));
            r.add(a.lastVerifiedAt() == null ? null : a.lastVerifiedAt().atZoneSameInstant(TZ).toLocalDate());
            rows.add(r);
        }
        audit.record("ASSET_REGISTER_DOWNLOADED", "Asset", null, Map.of("assets", all.size()));
        return new SimpleXlsx().sheet("Asset register", List.of("Tag", "Name", "Category", "Serial no", "Campus", "Room or place", "Department", "Custodian",
                "Acquired", "Cost (TZS)", "Life (years)", "Book value (TZS)", "Funded by", "Condition", "Status", "Last verified"), rows,
                18, 30, 16, 16, 16, 20, 20, 20, 12, 14, 8, 14, 12, 12, 12, 14).bytes();
    }

    // ------------------------------------------------------------------ verification

    @Transactional(readOnly = true)
    public List<VerificationView> verifications() {
        return jdbc.query(VER + " order by v.started_at desc", (rs, n) -> verification(rs));
    }

    @Transactional
    public VerificationDetail startVerification(String name, Long campusId) {
        Integer open = jdbc.queryForObject("select count(*) from asset_verifications where closed_at is null", Integer.class);
        if (open != null && open > 0) throw new BusinessRuleException("VERIFICATION_OPEN", "Close the verification under way before starting another");
        String n = name == null || name.isBlank() ? "Asset verification " + LocalDate.now(TZ).getYear() : cut(name, 120);
        Long id = jdbc.queryForObject("insert into asset_verifications (tenant_id, name, campus_id, started_by_name) values (cims_current_tenant(), ?, ?, ?) returning id",
                Long.class, n, campusId, CurrentUser.name());
        int lines = jdbc.update("""
                insert into asset_verification_lines (tenant_id, verification_id, asset_id)
                select cims_current_tenant(), ?, id from assets where status not in ('DISPOSED', 'LOST') and (?::bigint is null or campus_id = ?)""",
                id, campusId, campusId);
        if (lines == 0) throw new BusinessRuleException("NO_ASSETS", "There are no assets to verify");
        audit.record("ASSET_VERIFICATION_STARTED", "AssetVerification", id, Map.of("assets", lines));
        return verification(id);
    }

    @Transactional(readOnly = true)
    public VerificationDetail verification(Long id) {
        VerificationView v = jdbc.query(VER + " where v.id = ?", (rs, n) -> verification(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Verification not found"));
        List<VerificationLine> lines = jdbc.query("""
                select l.id, a.id, a.tag_no, a.name, coalesce(tv.name, a.location), d.name, a.condition, l.found, l.condition, l.note, l.checked_by_name, l.checked_at
                from asset_verification_lines l join assets a on a.id = l.asset_id left join teaching_venues tv on tv.id = a.venue_id
                left join departments d on d.id = a.department_id where l.verification_id = ? order by a.tag_no""", (rs, n) -> new VerificationLine(rs.getLong(1),
                rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), (Boolean) rs.getObject(8), rs.getString(9),
                rs.getString(10), rs.getString(11), odt(rs.getTimestamp(12))), id);
        return new VerificationDetail(v, lines);
    }

    /** Records one asset as found (with its condition) or missing. */
    @Transactional
    public VerificationLine check(Long lineId, CheckRequest r) {
        Map<String, Object> l = jdbc.queryForList("""
                select l.verification_id, v.closed_at from asset_verification_lines l join asset_verifications v on v.id = l.verification_id where l.id = ?""",
                lineId).stream().findFirst().orElseThrow(() -> new NotFoundException("Line not found"));
        if (l.get("closed_at") != null) throw new BusinessRuleException("VERIFICATION_CLOSED", "This verification is closed");
        if (r.found() == null) throw new BusinessRuleException("FOUND_REQUIRED", "Say whether the asset was found");
        String condition = Boolean.TRUE.equals(r.found()) ? (r.condition() == null || r.condition().isBlank() ? null : oneOf(r.condition(), CONDITIONS, "the condition")) : null;
        jdbc.update("update asset_verification_lines set found = ?, condition = ?, note = ?, checked_by_name = ?, checked_at = now() where id = ?",
                r.found(), condition, cut(r.note(), 300), CurrentUser.name(), lineId);
        return verification((Long) l.get("verification_id")).lines().stream().filter(x -> x.id().equals(lineId)).findFirst().orElseThrow();
    }

    /** Scans a tag during the verification: the asset is found where it is now. */
    @Transactional
    public VerificationLine scan(Long verificationId, String tag, String condition) {
        Long lineId = jdbc.query("""
                select l.id from asset_verification_lines l join assets a on a.id = l.asset_id where l.verification_id = ? and lower(a.tag_no) = lower(?)""",
                (rs, n) -> rs.getLong(1), verificationId, tag == null ? "" : tag.trim()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("The tag " + tag + " is not in this verification"));
        return check(lineId, new CheckRequest(true, condition, null));
    }

    /**
     * Closes the verification: found assets get the date and the condition seen; missing ones are listed in the report (they are marked
     * lost only when the officer says so, after a search).
     */
    @Transactional
    public VerificationDetail closeVerification(Long id, boolean markMissingLost) {
        VerificationDetail d = verification(id);
        if (d.verification().closedAt() != null) throw new BusinessRuleException("VERIFICATION_CLOSED", "This verification is already closed");
        for (VerificationLine l : d.lines()) {
            if (Boolean.TRUE.equals(l.found())) {
                String cond = l.condition() == null ? l.expectedCondition() : l.condition();
                jdbc.update("update assets set last_verified_at = ?, condition = ?, updated_at = now() where id = ?", Timestamp.from(l.checkedAt().toInstant()), cond, l.assetId());
                event(l.assetId(), "VERIFIED", "Found in " + d.verification().name() + (cond.equals(l.expectedCondition()) ? "" : ", condition " + label(cond)));
            } else if (Boolean.FALSE.equals(l.found()) && markMissingLost) {
                jdbc.update("update assets set status = 'LOST', updated_at = now() where id = ?", l.assetId());
                event(l.assetId(), "STATUS", "Not found in " + d.verification().name() + "; marked lost");
            }
        }
        jdbc.update("update asset_verifications set closed_by_name = ?, closed_at = now() where id = ?", CurrentUser.name(), id);
        audit.record("ASSET_VERIFICATION_CLOSED", "AssetVerification", id, Map.of("found", d.verification().found(), "missing", d.verification().missing(),
                "unchecked", d.verification().unchecked(), "markedLost", markMissingLost));
        return verification(id);
    }

    @Transactional(readOnly = true)
    public byte[] verificationReport(Long id) {
        VerificationDetail d = verification(id);
        List<List<Object>> rows = new ArrayList<>();
        for (VerificationLine l : d.lines()) {
            rows.add(java.util.Arrays.asList(l.tagNo(), l.name(), l.location(), l.department(),
                    l.found() == null ? "Not checked" : l.found() ? "Found" : "Missing", l.condition() == null ? null : label(l.condition()), l.note(),
                    l.checkedByName(), l.checkedAt() == null ? null : l.checkedAt().atZoneSameInstant(TZ).toLocalDate()));
        }
        return new SimpleXlsx().sheet("Verification", List.of("Tag", "Name", "Place", "Department", "Result", "Condition", "Note", "Checked by", "Checked on"),
                rows, 18, 30, 20, 20, 12, 12, 30, 20, 12).bytes();
    }

    // ------------------------------------------------------------------ helpers

    private static final String BASE = """
            select a.*, c.name as campus, v.name as venue, d.name as department from assets a left join campuses c on c.id = a.campus_id
            left join teaching_venues v on v.id = a.venue_id left join departments d on d.id = a.department_id""";

    private static final String VER = """
            select v.id, v.name, c.name as campus, v.started_by_name, v.started_at, v.closed_by_name, v.closed_at,
                   (select count(*) from asset_verification_lines l where l.verification_id = v.id) as total,
                   (select count(*) from asset_verification_lines l where l.verification_id = v.id and l.found) as found,
                   (select count(*) from asset_verification_lines l where l.verification_id = v.id and l.found = false) as missing,
                   (select count(*) from asset_verification_lines l where l.verification_id = v.id and l.found is null) as unchecked
            from asset_verifications v left join campuses c on c.id = v.campus_id""";

    private AssetView view(ResultSet rs) throws SQLException {
        BigDecimal cost = rs.getBigDecimal("cost");
        Integer life = (Integer) rs.getObject("useful_life_years");
        LocalDate acquired = rs.getObject("acquired_on", LocalDate.class);
        return new AssetView(rs.getLong("id"), rs.getString("tag_no"), rs.getString("name"), rs.getString("category"), rs.getString("description"),
                rs.getString("serial_no"), (Long) rs.getObject("campus_id"), rs.getString("campus"), (Long) rs.getObject("venue_id"), rs.getString("venue"),
                rs.getString("location"), (Long) rs.getObject("department_id"), rs.getString("department"), rs.getString("custodian_name"), acquired, cost, life,
                bookValue(cost, life, acquired, rs.getString("category")), rs.getString("funding"), rs.getString("supplier"), rs.getString("condition"),
                rs.getString("status"), rs.getObject("disposed_on", LocalDate.class), rs.getString("disposal_note"), odt(rs.getTimestamp("last_verified_at")));
    }

    /** Straight-line: cost × (1 − whole months used ÷ months of useful life); land keeps its cost; unknown life or date, no figure. */
    static BigDecimal bookValue(BigDecimal cost, Integer life, LocalDate acquired, String category) {
        if (cost == null) return null;
        if ("LAND".equals(category)) return cost;
        if (life == null || acquired == null) return null;
        long months = Math.max(0, ChronoUnit.MONTHS.between(acquired, LocalDate.now(TZ)));
        BigDecimal left = BigDecimal.ONE.subtract(BigDecimal.valueOf(months).divide(BigDecimal.valueOf(life * 12L), 6, RoundingMode.HALF_UP));
        return cost.multiply(left.max(BigDecimal.ZERO)).setScale(0, RoundingMode.HALF_UP);
    }

    private VerificationView verification(ResultSet rs) throws SQLException {
        return new VerificationView(rs.getLong("id"), rs.getString("name"), rs.getString("campus"), rs.getString("started_by_name"), odt(rs.getTimestamp("started_at")),
                rs.getString("closed_by_name"), odt(rs.getTimestamp("closed_at")), rs.getInt("total"), rs.getInt("found"), rs.getInt("missing"), rs.getInt("unchecked"));
    }

    private String place(Long campusId, Long venueId, String location, Long departmentId) {
        List<String> parts = new ArrayList<>();
        if (campusId != null) parts.add(jdbc.queryForObject("select name from campuses where id = ?", String.class, campusId));
        if (venueId != null) parts.add(jdbc.queryForObject("select name from teaching_venues where id = ?", String.class, venueId));
        if (location != null && !location.isBlank()) parts.add(location.trim());
        if (departmentId != null) parts.add(jdbc.queryForObject("select name from departments where id = ?", String.class, departmentId));
        return String.join(", ", parts);
    }

    private void validate(AssetRequest r) {
        if (r.cost() != null && r.cost().signum() < 0) throw new BusinessRuleException("COST_INVALID", "The cost cannot be negative");
        if (r.usefulLifeYears() != null && (r.usefulLifeYears() < 1 || r.usefulLifeYears() > 100)) throw new BusinessRuleException("LIFE_INVALID", "Useful life is 1 to 100 years");
        if (r.acquiredOn() != null && r.acquiredOn().isAfter(LocalDate.now(TZ))) throw new BusinessRuleException("DATE_INVALID", "The date acquired is in the future");
    }

    private void event(Long assetId, String kind, String details) {
        jdbc.update("insert into asset_events (tenant_id, asset_id, kind, details, by_name) values (cims_current_tenant(), ?, ?, ?, ?)",
                assetId, kind, details.length() > 500 ? details.substring(0, 500) : details, CurrentUser.name());
    }

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }

    static String label(String code) {
        if (code == null) return null;
        return switch (code) {
            case "ICT" -> "ICT";
            case "LAB_EQUIPMENT" -> "Laboratory equipment";
            default -> code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    static String money(BigDecimal b) {
        return String.format(Locale.ENGLISH, "%,.0f", b);
    }

    private static String oneOf(String v, Set<String> allowed, String what) {
        String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new BusinessRuleException("FIELD_INVALID", "Choose " + what);
        return u;
    }

    private static String required(String s, int max, String what) {
        if (s == null || s.isBlank()) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return cut(s, max);
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
