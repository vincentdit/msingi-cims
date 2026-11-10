package tz.co.msingi.cims.assets;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stores (FR-AST-04): consumable items with what is on hand, receipts from suppliers (valued at the weighted average cost), issues to
 * departments, adjustments after a stock take, items at or below their reorder level, and the stock report.
 */
@Service
@RequiredArgsConstructor
public class StoreService {

    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record ItemRequest(String code, String name, String unit, BigDecimal reorderLevel, Boolean active) {
    }

    public record ItemView(Long id, String code, String name, String unit, BigDecimal reorderLevel, BigDecimal onHand, BigDecimal averageCost,
                           BigDecimal value, boolean low, boolean active) {
    }

    public record MovementRequest(String kind, BigDecimal quantity, BigDecimal unitCost, Long departmentId, String party, String reference, String note) {
    }

    public record Movement(Long id, Long itemId, String itemCode, String itemName, String unit, String kind, BigDecimal quantity, BigDecimal unitCost,
                           BigDecimal balanceAfter, String department, String party, String reference, String note, String byName, OffsetDateTime at) {
    }

    @Transactional(readOnly = true)
    public List<ItemView> items(String q, boolean lowOnly) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select id, code, name, unit, reorder_level, on_hand, average_cost, active from store_items
                where (lower(code) like ? or lower(name) like ?) and (not ? or (active and on_hand <= reorder_level)) order by name""",
                (rs, n) -> {
                    BigDecimal on = rs.getBigDecimal(6), cost = rs.getBigDecimal(7), re = rs.getBigDecimal(5);
                    return new ItemView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), re, on, cost,
                            on.multiply(cost).setScale(0, RoundingMode.HALF_UP), rs.getBoolean(8) && on.compareTo(re) <= 0, rs.getBoolean(8));
                }, like, like, lowOnly);
    }

    @Transactional
    public ItemView saveItem(Long id, ItemRequest r) {
        String name = r.name() == null || r.name().isBlank() ? null : r.name().trim();
        String unit = r.unit() == null || r.unit().isBlank() ? null : r.unit().trim();
        if (name == null || unit == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give the name and the unit (pieces, reams, boxes, litres)");
        BigDecimal reorder = r.reorderLevel() == null ? BigDecimal.ZERO : r.reorderLevel();
        if (reorder.signum() < 0) throw new BusinessRuleException("REORDER_INVALID", "The reorder level cannot be negative");
        if (id == null) {
            String code = r.code() == null || r.code().isBlank() ? null : r.code().trim().toUpperCase(Locale.ROOT);
            if (code == null || !code.matches("[A-Z0-9/._-]{1,30}")) throw new BusinessRuleException("CODE_INVALID", "Give the item code (letters, digits, / . _ -)");
            Integer n = jdbc.queryForObject("select count(*) from store_items where code = ?", Integer.class, code);
            if (n != null && n > 0) throw new BusinessRuleException("CODE_TAKEN", "There is already an item " + code);
            id = jdbc.queryForObject("insert into store_items (tenant_id, code, name, unit, reorder_level) values (cims_current_tenant(), ?, ?, ?, ?) returning id",
                    Long.class, code, cut(name, 150), cut(unit, 20), reorder);
            audit.record("STORE_ITEM_ADDED", "StoreItem", id, Map.of("code", code));
        } else {
            if (jdbc.update("update store_items set name = ?, unit = ?, reorder_level = ?, active = ? where id = ?", cut(name, 150), cut(unit, 20), reorder,
                    !Boolean.FALSE.equals(r.active()), id) == 0) throw new NotFoundException("Item not found");
            audit.record("STORE_ITEM_CHANGED", "StoreItem", id, Map.of("name", name));
        }
        Long itemId = id;
        return items(null, false).stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    }

    /**
     * RECEIPT (a positive quantity from a supplier, with the unit cost), ISSUE (a positive quantity given out, to a department or a
     * person), ADJUSTMENT (positive or negative, with the reason, after counting the stock).
     */
    @Transactional
    public Movement move(Long itemId, MovementRequest r) {
        Map<String, Object> item = jdbc.queryForList("select on_hand, average_cost, active, name from store_items where id = ? for update", itemId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Item not found"));
        String kind = r.kind() == null ? "" : r.kind().toUpperCase(Locale.ROOT);
        BigDecimal q = r.quantity();
        if (q == null || q.signum() == 0) throw new BusinessRuleException("QUANTITY_REQUIRED", "Give the quantity");
        BigDecimal onHand = (BigDecimal) item.get("on_hand"), avg = (BigDecimal) item.get("average_cost");
        BigDecimal delta;
        BigDecimal newAvg = avg;
        switch (kind) {
            case "RECEIPT" -> {
                if (q.signum() < 0) throw new BusinessRuleException("QUANTITY_INVALID", "A receipt adds a positive quantity");
                if (r.unitCost() == null || r.unitCost().signum() < 0) throw new BusinessRuleException("COST_REQUIRED", "Give the unit cost from the delivery note or invoice");
                if (!Boolean.TRUE.equals(item.get("active"))) throw new BusinessRuleException("ITEM_INACTIVE", "The item is no longer stocked");
                delta = q;
                BigDecimal total = onHand.add(q);
                newAvg = onHand.multiply(avg).add(q.multiply(r.unitCost())).divide(total, 2, RoundingMode.HALF_UP);
            }
            case "ISSUE" -> {
                if (q.signum() < 0) throw new BusinessRuleException("QUANTITY_INVALID", "Give the quantity issued as a positive number");
                if (r.departmentId() == null && (r.party() == null || r.party().isBlank())) {
                    throw new BusinessRuleException("ISSUED_TO_REQUIRED", "Say which department or person the items were issued to");
                }
                delta = q.negate();
            }
            case "ADJUSTMENT" -> {
                if (r.note() == null || r.note().isBlank()) throw new BusinessRuleException("REASON_REQUIRED", "Say why the stock is adjusted (stock take, damage, expiry)");
                delta = q;
            }
            default -> throw new BusinessRuleException("KIND_INVALID", "Receipt, issue or adjustment");
        }
        BigDecimal after = onHand.add(delta);
        if (after.signum() < 0) {
            throw new BusinessRuleException("NOT_ENOUGH_STOCK", "Only " + onHand.stripTrailingZeros().toPlainString() + " in stock");
        }
        jdbc.update("update store_items set on_hand = ?, average_cost = ? where id = ?", after, newAvg, itemId);
        Long id = jdbc.queryForObject("""
                insert into store_transactions (tenant_id, item_id, kind, quantity, unit_cost, balance_after, department_id, party, reference, note, by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, itemId, kind, delta,
                "RECEIPT".equals(kind) ? r.unitCost() : avg, after, "ISSUE".equals(kind) ? r.departmentId() : null, cut(r.party(), 150), cut(r.reference(), 60),
                cut(r.note(), 300), CurrentUser.name());
        audit.record("STORE_" + kind, "StoreItem", itemId, Map.of("quantity", delta, "balance", after));
        return movements(itemId, null, null).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Movement> movements(Long itemId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select t.id, t.item_id, i.code, i.name, i.unit, t.kind, t.quantity, t.unit_cost, t.balance_after, d.name, t.party, t.reference, t.note, t.by_name, t.at
                from store_transactions t join store_items i on i.id = t.item_id left join departments d on d.id = t.department_id
                where (?::bigint is null or t.item_id = ?) and (?::date is null or t.at >= ?::date) and (?::date is null or t.at < ?::date + 1)
                order by t.at desc, t.id desc limit 1000""", (rs, n) -> new Movement(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14), odt(rs.getTimestamp(15))), itemId, itemId, from, from, to, to);
    }

    @Transactional
    public byte[] stockReport() {
        List<List<Object>> rows = new ArrayList<>();
        for (ItemView i : items(null, false)) {
            if (!i.active() && i.onHand().signum() == 0) continue;
            rows.add(java.util.Arrays.asList(i.code(), i.name(), i.unit(), i.onHand(), i.reorderLevel(), i.averageCost(), i.value(), i.low() ? "Reorder" : null));
        }
        List<List<Object>> issues = new ArrayList<>();
        LocalDate since = LocalDate.now(TZ).withDayOfMonth(1).minusMonths(2);
        for (Movement m : movements(null, since, null)) {
            if (!"ISSUE".equals(m.kind())) continue;
            issues.add(java.util.Arrays.asList(m.at().atZoneSameInstant(TZ).toLocalDate(), m.itemCode(), m.itemName(), m.quantity().negate(), m.unit(),
                    m.department(), m.party(), m.reference(), m.byName()));
        }
        audit.record("STOCK_REPORT_DOWNLOADED", "StoreItem", null, Map.of("items", rows.size()));
        return new SimpleXlsx()
                .sheet("Stock", List.of("Code", "Item", "Unit", "On hand", "Reorder level", "Average cost (TZS)", "Value (TZS)", ""), rows, 14, 30, 10, 10, 12, 16, 14, 10)
                .sheet("Issues (3 months)", List.of("Date", "Code", "Item", "Quantity", "Unit", "Department", "Collected by", "Requisition", "Issued by"), issues,
                        12, 14, 30, 10, 10, 24, 24, 14, 20)
                .bytes();
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
