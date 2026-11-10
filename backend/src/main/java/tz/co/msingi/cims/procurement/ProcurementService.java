package tz.co.msingi.cims.procurement;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tz.co.msingi.cims.assets.AssetService;
import tz.co.msingi.cims.assets.StoreService;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Procurement (FR-PRC-01..05): a department asks to buy (requisition); its Head endorses; the Bursar (or the Principal) approves that
 * funds are available — nobody endorses or approves their own request. The Procurement Officer collects quotations, chooses one
 * (giving a reason when it is not the lowest) and issues the local purchase order. Stores receive the goods against the order —
 * not the person who issued it — and each delivery goes into stores (at the order price) or into the asset register (one asset per unit).
 */
@Service
@RequiredArgsConstructor
public class ProcurementService {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] PROCURE = {Role.INSTITUTION_ADMIN, Role.PROCUREMENT_OFFICER};
    static final Role[] APPROVE = {Role.INSTITUTION_ADMIN, Role.BURSAR, Role.MANAGEMENT_VIEWER};
    static final Role[] RECEIVE = {Role.INSTITUTION_ADMIN, Role.STORES_OFFICER};
    static final Role[] READ_ALL = {Role.INSTITUTION_ADMIN, Role.PROCUREMENT_OFFICER, Role.BURSAR, Role.ACCOUNTANT, Role.STORES_OFFICER, Role.MANAGEMENT_VIEWER,
            Role.AUDITOR};
    static final Set<String> ASSET_CATEGORIES = Set.of("FURNITURE", "ICT", "LAB_EQUIPMENT", "WORKSHOP", "VEHICLE", "BUILDING", "LAND", "OFFICE_EQUIPMENT", "OTHER");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExamAccess access;
    private final NumberSequenceService sequences;
    private final StoreService stores;
    private final AssetService assets;
    private final Messaging messaging;
    private final AuditService audit;

    public record SupplierRequest(String name, String tin, String phone, String email, String address, String supplies, Boolean active) {
    }

    public record Supplier(Long id, String name, String tin, String phone, String email, String address, String supplies, boolean active, int orders,
                           BigDecimal ordered) {
    }

    public record LineRequest(String description, BigDecimal quantity, String unit, BigDecimal estimatedCost, Long storeItemId, String assetCategory) {
    }

    public record RequisitionRequest(Long departmentId, String title, String justification, LocalDate neededBy, List<LineRequest> lines) {
    }

    public record Line(Long id, String description, BigDecimal quantity, String unit, BigDecimal estimatedCost, BigDecimal amount, Long storeItemId,
                       String storeItem, String assetCategory) {
    }

    public record Quotation(Long id, Long supplierId, String supplier, BigDecimal amount, Integer deliveryDays, String reference, String fileName,
                            boolean chosen, String choiceNote, String createdByName, OffsetDateTime createdAt, boolean lowest) {
    }

    public record RequisitionView(Long id, String reqNo, Long departmentId, String department, String title, String justification, LocalDate neededBy,
                                  BigDecimal estimatedTotal, String status, String requestedByName, OffsetDateTime requestedAt, String endorsedByName,
                                  OffsetDateTime endorsedAt, String approvedByName, OffsetDateTime approvedAt, String decisionNote, Long orderId, String lpoNo,
                                  boolean canEndorse, boolean canApprove, boolean mine) {
    }

    public record RequisitionDetail(RequisitionView requisition, List<Line> lines, List<Quotation> quotations) {
    }

    public record DecisionRequest(boolean approve, String note) {
    }

    public record OrderLineRequest(Long requisitionLineId, BigDecimal quantity, BigDecimal unitPrice) {
    }

    public record OrderRequest(Long requisitionId, LocalDate deliverBy, String terms, List<OrderLineRequest> lines) {
    }

    public record OrderLine(Long id, String description, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount, BigDecimal received,
                            Long storeItemId, String storeItem, String assetCategory) {
    }

    public record Receipt(Long id, String grnNo, String deliveryNote, String invoiceRef, String note, BigDecimal value, String receivedByName,
                          OffsetDateTime receivedAt, List<Map<String, Object>> lines) {
    }

    public record OrderView(Long id, String lpoNo, Long requisitionId, String reqNo, String department, Long supplierId, String supplier, BigDecimal total,
                            BigDecimal receivedValue, LocalDate deliverBy, String terms, String status, String issuedByName, OffsetDateTime issuedAt,
                            String cancelNote) {
    }

    public record OrderDetail(OrderView order, List<OrderLine> lines, List<Receipt> receipts) {
    }

    public record ReceiveLine(Long orderLineId, BigDecimal quantity) {
    }

    public record ReceiveRequest(String deliveryNote, String invoiceRef, String note, List<ReceiveLine> lines) {
    }

    public record Overview(int toEndorse, int toApprove, int approvedWithoutOrder, int openOrders, BigDecimal orderedThisYear, BigDecimal receivedThisYear) {
    }

    // ================================================================== suppliers

    @Transactional(readOnly = true)
    public List<Supplier> suppliers(String q, boolean activeOnly) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select s.id, s.name, s.tin, s.phone, s.email, s.address, s.supplies, s.active,
                       (select count(*) from purchase_orders o where o.supplier_id = s.id and o.status <> 'CANCELLED'),
                       (select coalesce(sum(o.total), 0) from purchase_orders o where o.supplier_id = s.id and o.status <> 'CANCELLED')
                from suppliers s where (lower(s.name) like ? or lower(coalesce(s.supplies, '')) like ?) and (not ? or s.active) order by s.name""",
                (rs, n) -> new Supplier(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getBoolean(8), rs.getInt(9), rs.getBigDecimal(10)), like, like, activeOnly);
    }

    @Transactional
    public Supplier saveSupplier(Long id, SupplierRequest r) {
        access.require("keep suppliers", PROCURE);
        String name = required(r.name(), 150, "the supplier's name");
        String tin = cut(r.tin(), 20);
        if (tin != null && !tin.replace("-", "").matches("\\d{9}")) throw new BusinessRuleException("TIN_INVALID", "A TIN has 9 digits, e.g. 123-456-789");
        Integer same = jdbc.queryForObject("select count(*) from suppliers where lower(name) = lower(?) and (?::bigint is null or id <> ?)", Integer.class, name, id, id);
        if (same != null && same > 0) throw new BusinessRuleException("SUPPLIER_EXISTS", "There is already a supplier called " + name);
        if (id == null) {
            id = jdbc.queryForObject("""
                    insert into suppliers (tenant_id, name, tin, phone, email, address, supplies) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""",
                    Long.class, name, tin, cut(r.phone(), 20), cut(r.email(), 120), cut(r.address(), 300), cut(r.supplies(), 200));
            audit.record("SUPPLIER_ADDED", "Supplier", id, Map.of("name", name));
        } else {
            if (jdbc.update("update suppliers set name = ?, tin = ?, phone = ?, email = ?, address = ?, supplies = ?, active = ? where id = ?", name, tin,
                    cut(r.phone(), 20), cut(r.email(), 120), cut(r.address(), 300), cut(r.supplies(), 200), !Boolean.FALSE.equals(r.active()), id) == 0) {
                throw new NotFoundException("Supplier not found");
            }
            audit.record("SUPPLIER_CHANGED", "Supplier", id, Map.of("name", name));
        }
        Long sid = id;
        return suppliers(null, false).stream().filter(s -> s.id().equals(sid)).findFirst().orElseThrow();
    }

    // ================================================================== requisitions

    @Transactional
    public RequisitionDetail requisition(RequisitionRequest r) {
        String title = required(r.title(), 200, "what is to be bought");
        String why = required(r.justification(), 1000, "why it is needed");
        if (r.lines() == null || r.lines().isEmpty()) throw new BusinessRuleException("LINES_REQUIRED", "List at least one item");
        if (r.neededBy() != null && r.neededBy().isBefore(LocalDate.now(TZ))) throw new BusinessRuleException("DATE_INVALID", "The date needed is in the past");
        String no = NumberSequenceService.format(TenantContext.require().code() + "/PR/" + LocalDate.now(TZ).getYear() + "/", sequences.next("REQUISITION"), 5);
        Long me = CurrentUser.id().orElse(null);
        // without a department, or when the person asking is its Head, there is no Head of Department step; the Bursar's approval remains
        boolean skip = r.departmentId() == null || access.headsDepartment(r.departmentId());
        Long id = jdbc.queryForObject("""
                insert into purchase_requisitions (tenant_id, req_no, department_id, title, justification, needed_by, status, requested_by, requested_by_name,
                  endorsed_by_name, endorsed_at)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, no, r.departmentId(), title, why, r.neededBy(),
                skip ? "ENDORSED" : "SUBMITTED", me, CurrentUser.name(), skip ? (r.departmentId() == null ? "No department" : CurrentUser.name() + " (Head)") : null,
                skip ? Timestamp.from(java.time.Instant.now()) : null);
        BigDecimal total = BigDecimal.ZERO;
        int i = 0;
        for (LineRequest l : r.lines()) {
            String d = required(l.description(), 300, "each item's description");
            if (l.quantity() == null || l.quantity().signum() <= 0) throw new BusinessRuleException("QUANTITY_REQUIRED", "Give the quantity of " + d);
            if (l.estimatedCost() == null || l.estimatedCost().signum() < 0) throw new BusinessRuleException("COST_REQUIRED", "Give the estimated unit cost of " + d);
            String cat = l.assetCategory() == null || l.assetCategory().isBlank() ? null : oneOf(l.assetCategory(), ASSET_CATEGORIES, "the asset category");
            if (cat != null && l.storeItemId() != null) throw new BusinessRuleException("LINE_TARGET", d + " goes either into stores or into the asset register");
            if (cat != null && l.quantity().stripTrailingZeros().scale() > 0) throw new BusinessRuleException("QUANTITY_WHOLE", "Assets are counted in whole units");
            String unit = l.storeItemId() != null
                    ? jdbc.query("select unit from store_items where id = ?", (rs, n) -> rs.getString(1), l.storeItemId()).stream().findFirst()
                    .orElseThrow(() -> new NotFoundException("Stores item not found"))
                    : required(l.unit(), 20, "the unit of " + d);
            jdbc.update("""
                    insert into requisition_lines (tenant_id, requisition_id, description, quantity, unit, estimated_cost, store_item_id, asset_category, sort_order)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?)""", id, d, l.quantity(), unit, l.estimatedCost(), l.storeItemId(), cat, i++);
            total = total.add(l.quantity().multiply(l.estimatedCost()));
        }
        jdbc.update("update purchase_requisitions set estimated_total = ? where id = ?", total.setScale(2, RoundingMode.HALF_UP), id);
        audit.record("REQUISITION_SUBMITTED", "PurchaseRequisition", id, Map.of("reqNo", no, "total", total));
        return detail(id);
    }

    /** Requisitions: all for procurement, finance, stores, management and audit; a Head of Department's own department; otherwise one's own. */
    @Transactional(readOnly = true)
    public List<RequisitionView> requisitions(String status, boolean mineOnly) {
        boolean all = access.has(READ_ALL) && !mineOnly;
        boolean hod = access.has(Role.HEAD_OF_DEPARTMENT) && !mineOnly;
        Long me = CurrentUser.id().orElse(-1L);
        return jdbc.query(REQ + " where (?::text is null or r.status = ?) order by r.requested_at desc limit 500", (rs, n) -> req(rs), blank(status), blank(status))
                .stream().filter(r -> all || r.mine() || (hod && r.departmentId() != null && access.headsDepartment(r.departmentId()))).toList();
    }

    @Transactional(readOnly = true)
    public RequisitionDetail detail(Long id) {
        RequisitionView r = jdbc.query(REQ + " where r.id = ?", (rs, n) -> req(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Requisition not found"));
        if (!access.has(READ_ALL) && !r.mine() && !(r.departmentId() != null && access.headsDepartment(r.departmentId()))) {
            throw new AccessDeniedException("Not allowed to see this requisition");
        }
        List<Line> lines = jdbc.query("""
                select l.id, l.description, l.quantity, l.unit, l.estimated_cost, l.store_item_id, i.code || ' ' || i.name, l.asset_category
                from requisition_lines l left join store_items i on i.id = l.store_item_id where l.requisition_id = ? order by l.sort_order""",
                (rs, n) -> new Line(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(3).multiply(rs.getBigDecimal(5)).setScale(2, RoundingMode.HALF_UP), (Long) rs.getObject(6, Long.class), rs.getString(7),
                        rs.getString(8)), id);
        List<Quotation> qs = quotations(id);
        return new RequisitionDetail(r, lines, qs);
    }

    @Transactional
    public RequisitionDetail endorse(Long id, DecisionRequest d) {
        Map<String, Object> r = lockReq(id);
        Long dept = r.get("department_id") == null ? null : ((Number) r.get("department_id")).longValue();
        if (!access.has(Role.INSTITUTION_ADMIN) && !(dept != null && access.headsDepartment(dept))) {
            throw new AccessDeniedException("The Head of the department endorses its requisitions");
        }
        if (!"SUBMITTED".equals(r.get("status"))) throw new BusinessRuleException("REQUISITION_NOT_WAITING", "This requisition is not waiting for endorsement");
        own(r, "requested_by", "You asked for this; someone else endorses it");
        if (!d.approve() && cut(d.note(), 500) == null) throw new BusinessRuleException("REASON_REQUIRED", "Say why it is not endorsed");
        jdbc.update("update purchase_requisitions set status = ?, endorsed_by = ?, endorsed_by_name = ?, endorsed_at = now(), decision_note = ? where id = ?",
                d.approve() ? "ENDORSED" : "REJECTED", CurrentUser.id().orElse(null), CurrentUser.name(), cut(d.note(), 500), id);
        if (!d.approve()) tell(r, "was not endorsed by the Head of Department");
        audit.record(d.approve() ? "REQUISITION_ENDORSED" : "REQUISITION_REJECTED", "PurchaseRequisition", id, Map.of());
        return detail(id);
    }

    @Transactional
    public RequisitionDetail approve(Long id, DecisionRequest d) {
        access.require("approve requisitions", APPROVE);
        Map<String, Object> r = lockReq(id);
        if (!"ENDORSED".equals(r.get("status"))) throw new BusinessRuleException("REQUISITION_NOT_WAITING", "This requisition is not waiting for approval");
        own(r, "requested_by", "You asked for this; someone else approves it");
        own(r, "endorsed_by", "You endorsed this; someone else approves it");
        if (!d.approve() && cut(d.note(), 500) == null) throw new BusinessRuleException("REASON_REQUIRED", "Say why it is not approved");
        jdbc.update("update purchase_requisitions set status = ?, approved_by = ?, approved_by_name = ?, approved_at = now(), decision_note = coalesce(?, decision_note) "
                + "where id = ?", d.approve() ? "APPROVED" : "REJECTED", CurrentUser.id().orElse(null), CurrentUser.name(), cut(d.note(), 500), id);
        tell(r, d.approve() ? "is approved; Procurement will buy it" : "was not approved" + (d.note() == null ? "" : ": " + cut(d.note(), 120)));
        audit.record(d.approve() ? "REQUISITION_APPROVED" : "REQUISITION_REJECTED", "PurchaseRequisition", id, Map.of("total", r.get("estimated_total")));
        return detail(id);
    }

    @Transactional
    public RequisitionDetail cancel(Long id, String note) {
        Map<String, Object> r = lockReq(id);
        boolean mine = Objects.equals(r.get("requested_by") == null ? null : ((Number) r.get("requested_by")).longValue(), CurrentUser.id().orElse(null));
        if (!mine && !access.has(PROCURE)) throw new AccessDeniedException("Only the person who asked, or Procurement, cancels a requisition");
        if (!Set.of("SUBMITTED", "ENDORSED", "APPROVED").contains((String) r.get("status"))) {
            throw new BusinessRuleException("REQUISITION_LOCKED", "An ordered, rejected or cancelled requisition cannot be cancelled");
        }
        jdbc.update("update purchase_requisitions set status = 'CANCELLED', decision_note = ? where id = ?", required(note, 500, "why it is cancelled"), id);
        audit.record("REQUISITION_CANCELLED", "PurchaseRequisition", id, Map.of());
        return detail(id);
    }

    // ------------------------------------------------------------------ quotations

    @Transactional
    public RequisitionDetail addQuotation(Long reqId, Long supplierId, BigDecimal amount, Integer deliveryDays, String reference, MultipartFile file) {
        access.require("record quotations", PROCURE);
        Map<String, Object> r = lockReq(reqId);
        if (!"APPROVED".equals(r.get("status"))) throw new BusinessRuleException("REQUISITION_NOT_APPROVED", "Quotations are collected for an approved requisition");
        if (supplierId == null) throw new BusinessRuleException("SUPPLIER_REQUIRED", "Choose the supplier");
        Boolean active = jdbc.query("select active from suppliers where id = ?", (rs, n) -> rs.getBoolean(1), supplierId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Supplier not found"));
        if (!active) throw new BusinessRuleException("SUPPLIER_INACTIVE", "The supplier is no longer used");
        if (amount == null || amount.signum() <= 0) throw new BusinessRuleException("AMOUNT_REQUIRED", "Give the quoted total");
        UploadedFile f = file == null || file.isEmpty() ? null : UploadedFile.read(file, 5 * 1024 * 1024, UploadedFile.Allowed.PDF_OR_IMAGE);
        Integer dup = jdbc.queryForObject("select count(*) from quotations where requisition_id = ? and supplier_id = ?", Integer.class, reqId, supplierId);
        if (dup != null && dup > 0) throw new BusinessRuleException("QUOTATION_EXISTS", "This supplier's quotation is already recorded");
        Long id = jdbc.queryForObject("""
                insert into quotations (tenant_id, requisition_id, supplier_id, amount, delivery_days, reference, file_name, content_type, data, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, reqId, supplierId, amount, deliveryDays, cut(reference, 60),
                f == null ? null : f.fileName(), f == null ? null : f.contentType(), f == null ? null : f.data(), CurrentUser.name());
        audit.record("QUOTATION_ADDED", "PurchaseRequisition", reqId, Map.of("quotation", id, "amount", amount));
        return detail(reqId);
    }

    @Transactional
    public RequisitionDetail chooseQuotation(Long quotationId, String note) {
        access.require("choose a quotation", PROCURE);
        Map<String, Object> q = jdbc.queryForList("select requisition_id, amount from quotations where id = ?", quotationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quotation not found"));
        Long reqId = ((Number) q.get("requisition_id")).longValue();
        Map<String, Object> r = lockReq(reqId);
        if (!"APPROVED".equals(r.get("status"))) throw new BusinessRuleException("REQUISITION_NOT_APPROVED", "The requisition is already ordered or closed");
        BigDecimal lowest = jdbc.queryForObject("select min(amount) from quotations where requisition_id = ?", BigDecimal.class, reqId);
        boolean isLowest = ((BigDecimal) q.get("amount")).compareTo(lowest) == 0;
        if (!isLowest && cut(note, 500) == null) {
            throw new BusinessRuleException("REASON_REQUIRED", "This is not the lowest quotation; say why it is chosen (quality, delivery time, after-sales service)");
        }
        jdbc.update("update quotations set chosen = (id = ?), choice_note = case when id = ? then ? else null end where requisition_id = ?", quotationId,
                quotationId, cut(note, 500), reqId);
        audit.record("QUOTATION_CHOSEN", "PurchaseRequisition", reqId, Map.of("quotation", quotationId, "lowest", isLowest));
        return detail(reqId);
    }

    @Transactional(readOnly = true)
    public StoredFile quotationFile(Long id) {
        if (!access.has(READ_ALL)) throw new AccessDeniedException("Not allowed");
        return jdbc.query("select file_name, content_type, data from quotations where id = ? and data is not null",
                (rs, n) -> new StoredFile(rs.getString(1), rs.getString(2), rs.getBytes(3)), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No file"));
    }

    public record StoredFile(String fileName, String contentType, byte[] data) {
    }

    // ================================================================== purchase orders

    @Transactional
    public OrderDetail issueOrder(OrderRequest r) {
        access.require("issue purchase orders", PROCURE);
        if (r.requisitionId() == null) throw new BusinessRuleException("REQUISITION_REQUIRED", "Choose the requisition");
        Map<String, Object> req = lockReq(r.requisitionId());
        if (!"APPROVED".equals(req.get("status"))) throw new BusinessRuleException("REQUISITION_NOT_APPROVED", "An order is issued for an approved requisition");
        own(req, "requested_by", "You asked for this; someone else orders it");
        Map<String, Object> q = jdbc.queryForList("select id, supplier_id, amount from quotations where requisition_id = ? and chosen", r.requisitionId()).stream()
                .findFirst().orElseThrow(() -> new BusinessRuleException("QUOTATION_REQUIRED", "Choose a quotation first"));
        Map<Long, Map<String, Object>> reqLines = new LinkedHashMap<>();
        jdbc.queryForList("select * from requisition_lines where requisition_id = ? order by sort_order", r.requisitionId())
                .forEach(l -> reqLines.put(((Number) l.get("id")).longValue(), l));
        List<OrderLineRequest> lines = r.lines() == null || r.lines().isEmpty() ? List.of() : r.lines();
        if (lines.isEmpty()) throw new BusinessRuleException("PRICES_REQUIRED", "Give the unit price of each item from the chosen quotation");
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLineRequest l : lines) {
            Map<String, Object> rl = reqLines.get(l.requisitionLineId());
            if (rl == null) throw new BusinessRuleException("LINE_INVALID", "An item is not on the requisition");
            if (l.unitPrice() == null || l.unitPrice().signum() < 0) throw new BusinessRuleException("PRICE_REQUIRED", "Give the unit price of " + rl.get("description"));
            BigDecimal qty = l.quantity() == null ? (BigDecimal) rl.get("quantity") : l.quantity();
            if (qty.signum() <= 0 || qty.compareTo((BigDecimal) rl.get("quantity")) > 0) {
                throw new BusinessRuleException("QUANTITY_INVALID", "Order at most the quantity approved for " + rl.get("description"));
            }
            total = total.add(qty.multiply(l.unitPrice()));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo((BigDecimal) q.get("amount")) > 0) {
            throw new BusinessRuleException("ABOVE_QUOTATION", "The order (TZS " + money(total) + ") is more than the chosen quotation (TZS " + money((BigDecimal) q.get("amount")) + ")");
        }
        String no = NumberSequenceService.format(TenantContext.require().code() + "/LPO/" + LocalDate.now(TZ).getYear() + "/", sequences.next("LPO"), 5);
        Long id = jdbc.queryForObject("""
                insert into purchase_orders (tenant_id, lpo_no, requisition_id, supplier_id, quotation_id, total, deliver_by, terms, issued_by, issued_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, no, r.requisitionId(), q.get("supplier_id"), q.get("id"),
                total, r.deliverBy(), cut(r.terms(), 500), CurrentUser.id().orElse(null), CurrentUser.name());
        int i = 0;
        for (OrderLineRequest l : lines) {
            Map<String, Object> rl = reqLines.get(l.requisitionLineId());
            jdbc.update("""
                    insert into purchase_order_lines (tenant_id, order_id, requisition_line_id, description, quantity, unit, unit_price, store_item_id, asset_category, sort_order)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, l.requisitionLineId(), rl.get("description"),
                    l.quantity() == null ? rl.get("quantity") : l.quantity(), rl.get("unit"), l.unitPrice(), rl.get("store_item_id"), rl.get("asset_category"), i++);
        }
        jdbc.update("update purchase_requisitions set status = 'ORDERED' where id = ?", r.requisitionId());
        audit.record("LPO_ISSUED", "PurchaseOrder", id, Map.of("lpoNo", no, "total", total));
        return order(id);
    }

    @Transactional(readOnly = true)
    public List<OrderView> orders(String status) {
        if (!access.has(READ_ALL)) throw new AccessDeniedException("Not allowed to see purchase orders");
        return jdbc.query(ORDER + " where (?::text is null or o.status = ? or (? = 'OPEN' and o.status in ('ISSUED', 'PART_RECEIVED'))) order by o.issued_at desc limit 500",
                (rs, n) -> orderView(rs), blank(status), blank(status), blank(status));
    }

    @Transactional(readOnly = true)
    public OrderDetail order(Long id) {
        if (!access.has(READ_ALL)) throw new AccessDeniedException("Not allowed to see purchase orders");
        OrderView o = jdbc.query(ORDER + " where o.id = ?", (rs, n) -> orderView(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Order not found"));
        List<OrderLine> lines = jdbc.query("""
                select l.id, l.description, l.quantity, l.unit, l.unit_price, l.received, l.store_item_id, i.code || ' ' || i.name, l.asset_category
                from purchase_order_lines l left join store_items i on i.id = l.store_item_id where l.order_id = ? order by l.sort_order""",
                (rs, n) -> new OrderLine(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(3).multiply(rs.getBigDecimal(5)).setScale(2, RoundingMode.HALF_UP), rs.getBigDecimal(6), rs.getObject(7, Long.class),
                        rs.getString(8), rs.getString(9)), id);
        List<Receipt> receipts = jdbc.query("""
                select id, grn_no, delivery_note, invoice_ref, note, value, received_by_name, received_at, lines::text from goods_receipts
                where order_id = ? order by received_at""", (rs, n) -> new Receipt(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBigDecimal(6), rs.getString(7), odt(rs.getTimestamp(8)),
                mapper.readValue(rs.getString(9), new TypeReference<List<Map<String, Object>>>() {
                })), id);
        return new OrderDetail(o, lines, receipts);
    }

    @Transactional
    public OrderDetail cancelOrder(Long id, String note) {
        access.require("cancel purchase orders", PROCURE);
        Map<String, Object> o = lockOrder(id);
        if (!"ISSUED".equals(o.get("status"))) throw new BusinessRuleException("ORDER_RECEIVED", "Goods have been received against this order; it cannot be cancelled");
        jdbc.update("update purchase_orders set status = 'CANCELLED', cancel_note = ? where id = ?", required(note, 300, "why the order is cancelled"), id);
        jdbc.update("update purchase_requisitions set status = 'APPROVED' where id = ?", o.get("requisition_id"));
        audit.record("LPO_CANCELLED", "PurchaseOrder", id, Map.of());
        return order(id);
    }

    /** Goods received note: what arrived goes into stores at the order price, or into the asset register one asset per unit. */
    @Transactional
    public OrderDetail receive(Long orderId, ReceiveRequest r) {
        access.require("receive goods", RECEIVE);
        Map<String, Object> o = lockOrder(orderId);
        if (!Set.of("ISSUED", "PART_RECEIVED").contains((String) o.get("status"))) throw new BusinessRuleException("ORDER_CLOSED", "This order is closed");
        if (o.get("issued_by") != null && Objects.equals(((Number) o.get("issued_by")).longValue(), CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("ISSUED_BY_YOU", "You issued this order; Stores receive the goods");
        }
        if (r.lines() == null || r.lines().stream().noneMatch(l -> l.quantity() != null && l.quantity().signum() > 0)) {
            throw new BusinessRuleException("NOTHING_RECEIVED", "Give the quantity received of at least one item");
        }
        String supplier = jdbc.queryForObject("select name from suppliers where id = ?", String.class, o.get("supplier_id"));
        String lpo = (String) o.get("lpo_no");
        String grn = NumberSequenceService.format(TenantContext.require().code() + "/GRN/" + LocalDate.now(TZ).getYear() + "/", sequences.next("GRN"), 5);
        List<Map<String, Object>> done = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        for (ReceiveLine rl : r.lines()) {
            if (rl.quantity() == null || rl.quantity().signum() == 0) continue;
            if (rl.quantity().signum() < 0) throw new BusinessRuleException("QUANTITY_INVALID", "Quantities received are positive");
            Map<String, Object> l = jdbc.queryForList("select * from purchase_order_lines where id = ? and order_id = ? for update", rl.orderLineId(), orderId)
                    .stream().findFirst().orElseThrow(() -> new BusinessRuleException("LINE_INVALID", "An item is not on this order"));
            BigDecimal left = ((BigDecimal) l.get("quantity")).subtract((BigDecimal) l.get("received"));
            if (rl.quantity().compareTo(left) > 0) {
                throw new BusinessRuleException("MORE_THAN_ORDERED", "Only " + left.stripTrailingZeros().toPlainString() + " of " + l.get("description") + " are still due");
            }
            BigDecimal price = (BigDecimal) l.get("unit_price");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderLineId", rl.orderLineId());
            line.put("description", l.get("description"));
            line.put("quantity", rl.quantity());
            line.put("unit", l.get("unit"));
            line.put("unitPrice", price);
            if (l.get("store_item_id") != null) {
                stores.move(((Number) l.get("store_item_id")).longValue(), new StoreService.MovementRequest("RECEIPT", rl.quantity(), price, null, supplier, lpo,
                        "Goods received " + grn));
                line.put("into", "Stores");
            } else if (l.get("asset_category") != null) {
                if (rl.quantity().stripTrailingZeros().scale() > 0) throw new BusinessRuleException("QUANTITY_WHOLE", "Assets are received in whole units");
                List<String> tags = new ArrayList<>();
                for (int k = 0; k < rl.quantity().intValue(); k++) {
                    var a = assets.add(new AssetService.AssetRequest(null, cut((String) l.get("description"), 200), (String) l.get("asset_category"),
                            "Received on " + grn + " against " + lpo, null, null, null, null, null, null, LocalDate.now(TZ), price, null, null, supplier,
                            "NEW", "IN_STORE"));
                    tags.add(a.asset().tagNo());
                }
                line.put("into", "Asset register");
                line.put("assetTags", tags);
            } else {
                line.put("into", "Direct to the department");
            }
            jdbc.update("update purchase_order_lines set received = received + ? where id = ?", rl.quantity(), rl.orderLineId());
            value = value.add(rl.quantity().multiply(price));
            done.add(line);
        }
        jdbc.update("""
                insert into goods_receipts (tenant_id, grn_no, order_id, delivery_note, invoice_ref, note, lines, value, received_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", grn, orderId, cut(r.deliveryNote(), 60), cut(r.invoiceRef(), 60), cut(r.note(), 500),
                mapper.writeValueAsString(done), value.setScale(2, RoundingMode.HALF_UP), CurrentUser.name());
        Boolean complete = jdbc.queryForObject("select bool_and(received >= quantity) from purchase_order_lines where order_id = ?", Boolean.class, orderId);
        jdbc.update("update purchase_orders set status = ? where id = ?", Boolean.TRUE.equals(complete) ? "RECEIVED" : "PART_RECEIVED", orderId);
        audit.record("GOODS_RECEIVED", "PurchaseOrder", orderId, Map.of("grn", grn, "value", value));
        return order(orderId);
    }

    /** The local purchase order as a PDF to send to the supplier. */
    @Transactional
    public byte[] orderPdf(Long id) {
        OrderDetail d = order(id);
        OrderView o = d.order();
        Map<String, Object> s = jdbc.queryForMap("select name, tin, phone, email, address from suppliers where id = ?", o.supplierId());
        String inst = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (OrderLine l : d.lines()) {
            rows.append("<tr><td>").append(n++).append("</td><td>").append(e(l.description())).append("</td><td class=\"a\">")
                    .append(l.quantity().stripTrailingZeros().toPlainString()).append(' ').append(e(l.unit())).append("</td><td class=\"a\">")
                    .append(money(l.unitPrice())).append("</td><td class=\"a\">").append(money(l.amount())).append("</td></tr>");
        }
        String html = "<html><head><style>body{font-family:sans-serif;font-size:10pt;color:#0F1B2D}h1{font-size:15pt;margin:0}h2{font-size:12pt;margin:12pt 0 4pt}"
                + "table{width:100%;border-collapse:collapse}th,td{padding:4pt;border-bottom:0.5pt solid #ccd;text-align:left}td.a,th.a{text-align:right}"
                + ".tot td{font-weight:bold;border-top:1pt solid #0F1B2D}.muted{color:#4A5568;font-size:9pt}.sign{margin-top:36pt}</style></head><body>"
                + "<h1>" + e(inst) + "</h1><h2>Local Purchase Order " + e(o.lpoNo()) + "</h2>"
                + "<table><tr><td><b>To</b><br/>" + e((String) s.get("name")) + "<br/>" + e((String) s.get("address")) + "<br/>" + e((String) s.get("phone"))
                + (s.get("tin") == null ? "" : "<br/>TIN " + e((String) s.get("tin"))) + "</td><td><b>Date</b> " + DAY.format(o.issuedAt().atZoneSameInstant(TZ))
                + "<br/><b>Requisition</b> " + e(o.reqNo()) + (o.deliverBy() == null ? "" : "<br/><b>Deliver by</b> " + DAY.format(o.deliverBy()))
                + "</td></tr></table>"
                + "<p>Please supply the following, as in your quotation:</p>"
                + "<table><tr><th>#</th><th>Item</th><th class=\"a\">Quantity</th><th class=\"a\">Unit price (TZS)</th><th class=\"a\">Amount (TZS)</th></tr>"
                + rows + "<tr class=\"tot\"><td></td><td>Total</td><td></td><td></td><td class=\"a\">" + money(o.total()) + "</td></tr></table>"
                + (o.terms() == null ? "" : "<p><b>Terms:</b> " + e(o.terms()) + "</p>")
                + "<p class=\"muted\">Quote this order number on the delivery note and the invoice. Goods are received by Stores; payment follows the goods "
                + "received note and the invoice.</p>"
                + "<p class=\"sign\">Issued by " + e(o.issuedByName()) + ", Procurement</p></body></html>";
        audit.record("LPO_PRINTED", "PurchaseOrder", id, Map.of());
        return PdfRenderer.render(html, "purchase order");
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Overview o = jdbc.queryForObject("""
                select (select count(*) from purchase_requisitions where status = 'SUBMITTED'),
                       (select count(*) from purchase_requisitions where status = 'ENDORSED'),
                       (select count(*) from purchase_requisitions where status = 'APPROVED'),
                       (select count(*) from purchase_orders where status in ('ISSUED', 'PART_RECEIVED')),
                       (select coalesce(sum(total), 0) from purchase_orders where status <> 'CANCELLED' and issued_at >= date_trunc('year', now())),
                       (select coalesce(sum(value), 0) from goods_receipts where received_at >= date_trunc('year', now()))""",
                (rs, n) -> new Overview(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        return o;
    }

    /** The procurement register for the year: requisitions, orders and goods received (Excel). */
    @Transactional
    public byte[] register(int year) {
        if (!access.has(READ_ALL)) throw new AccessDeniedException("Not allowed");
        List<List<Object>> reqs = jdbc.query("""
                select r.req_no, r.requested_at, d.name, r.title, r.estimated_total, r.status, r.requested_by_name, r.endorsed_by_name, r.approved_by_name
                from purchase_requisitions r left join departments d on d.id = r.department_id
                where extract(year from r.requested_at at time zone 'Africa/Dar_es_Salaam') = ? order by r.requested_at""",
                (rs, n) -> Arrays.asList(rs.getString(1), date(rs.getTimestamp(2)), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)), year);
        List<List<Object>> orders = jdbc.query("""
                select o.lpo_no, o.issued_at, r.req_no, s.name, s.tin, o.total, o.status, o.issued_by_name,
                       (select count(*) from quotations q where q.requisition_id = o.requisition_id),
                       (select q.choice_note from quotations q where q.id = o.quotation_id)
                from purchase_orders o join purchase_requisitions r on r.id = o.requisition_id join suppliers s on s.id = o.supplier_id
                where extract(year from o.issued_at at time zone 'Africa/Dar_es_Salaam') = ? order by o.issued_at""",
                (rs, n) -> Arrays.asList(rs.getString(1), date(rs.getTimestamp(2)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
                        rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10)), year);
        List<List<Object>> grns = jdbc.query("""
                select g.grn_no, g.received_at, o.lpo_no, s.name, g.delivery_note, g.invoice_ref, g.value, g.received_by_name
                from goods_receipts g join purchase_orders o on o.id = g.order_id join suppliers s on s.id = o.supplier_id
                where extract(year from g.received_at at time zone 'Africa/Dar_es_Salaam') = ? order by g.received_at""",
                (rs, n) -> Arrays.asList(rs.getString(1), date(rs.getTimestamp(2)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getBigDecimal(7), rs.getString(8)), year);
        audit.record("PROCUREMENT_REGISTER_DOWNLOADED", "PurchaseOrder", null, Map.of("year", year));
        return new SimpleXlsx()
                .sheet("Requisitions", List.of("No", "Date", "Department", "For", "Estimate (TZS)", "Status", "Asked by", "Endorsed by", "Approved by"), reqs,
                        20, 12, 22, 34, 14, 12, 20, 20, 20)
                .sheet("Orders", List.of("LPO", "Date", "Requisition", "Supplier", "TIN", "Total (TZS)", "Status", "Issued by", "Quotations", "Reason for choice"),
                        orders, 20, 12, 20, 26, 12, 14, 14, 20, 10, 34)
                .sheet("Goods received", List.of("GRN", "Date", "LPO", "Supplier", "Delivery note", "Invoice", "Value (TZS)", "Received by"), grns,
                        20, 12, 20, 26, 14, 14, 14, 20)
                .bytes();
    }

    // ------------------------------------------------------------------ helpers

    private static final String REQ = """
            select r.*, d.name as department, o.id as order_id, o.lpo_no from purchase_requisitions r left join departments d on d.id = r.department_id
            left join purchase_orders o on o.requisition_id = r.id and o.status <> 'CANCELLED'""";

    private RequisitionView req(ResultSet rs) throws SQLException {
        Long me = CurrentUser.id().orElse(-1L);
        Long by = rs.getObject("requested_by") == null ? null : rs.getLong("requested_by");
        Long endorsedBy = rs.getObject("endorsed_by") == null ? null : rs.getLong("endorsed_by");
        Long dept = rs.getObject("department_id") == null ? null : rs.getLong("department_id");
        String status = rs.getString("status");
        boolean mine = Objects.equals(by, me);
        boolean canEndorse = "SUBMITTED".equals(status) && !mine && (access.has(Role.INSTITUTION_ADMIN) || (dept != null && access.headsDepartment(dept)));
        boolean canApprove = "ENDORSED".equals(status) && !mine && !Objects.equals(endorsedBy, me) && access.has(APPROVE);
        return new RequisitionView(rs.getLong("id"), rs.getString("req_no"), dept, rs.getString("department"), rs.getString("title"),
                rs.getString("justification"), rs.getObject("needed_by", LocalDate.class), rs.getBigDecimal("estimated_total"), status,
                rs.getString("requested_by_name"), odt(rs.getTimestamp("requested_at")), rs.getString("endorsed_by_name"), odt(rs.getTimestamp("endorsed_at")),
                rs.getString("approved_by_name"), odt(rs.getTimestamp("approved_at")), rs.getString("decision_note"),
                rs.getObject("order_id") == null ? null : rs.getLong("order_id"), rs.getString("lpo_no"), canEndorse, canApprove, mine);
    }

    private List<Quotation> quotations(Long reqId) {
        BigDecimal lowest = jdbc.queryForObject("select min(amount) from quotations where requisition_id = ?", BigDecimal.class, reqId);
        return jdbc.query("""
                select q.id, q.supplier_id, s.name, q.amount, q.delivery_days, q.reference, q.file_name, q.chosen, q.choice_note, q.created_by_name, q.created_at
                from quotations q join suppliers s on s.id = q.supplier_id where q.requisition_id = ? order by q.amount""",
                (rs, n) -> new Quotation(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4), (Integer) rs.getObject(5, Integer.class),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getString(10), odt(rs.getTimestamp(11)),
                        lowest != null && rs.getBigDecimal(4).compareTo(lowest) == 0), reqId);
    }

    private static final String ORDER = """
            select o.*, r.req_no, d.name as department, s.name as supplier,
                   (select coalesce(sum(g.value), 0) from goods_receipts g where g.order_id = o.id) as received_value
            from purchase_orders o join purchase_requisitions r on r.id = o.requisition_id join suppliers s on s.id = o.supplier_id
            left join departments d on d.id = r.department_id""";

    private static OrderView orderView(ResultSet rs) throws SQLException {
        return new OrderView(rs.getLong("id"), rs.getString("lpo_no"), rs.getLong("requisition_id"), rs.getString("req_no"), rs.getString("department"),
                rs.getLong("supplier_id"), rs.getString("supplier"), rs.getBigDecimal("total"), rs.getBigDecimal("received_value"),
                rs.getObject("deliver_by", LocalDate.class), rs.getString("terms"), rs.getString("status"), rs.getString("issued_by_name"),
                odt(rs.getTimestamp("issued_at")), rs.getString("cancel_note"));
    }

    private void tell(Map<String, Object> r, String outcome) {
        if (r.get("requested_by") == null) return;
        String phone = jdbc.queryForList("select phone from user_accounts where id = ? and phone is not null", String.class,
                ((Number) r.get("requested_by")).longValue()).stream().findFirst().orElse(null);
        messaging.event("REQUISITION_DECIDED", phone, Map.of("reqNo", r.get("req_no"), "outcome", outcome));
    }

    private void own(Map<String, Object> r, String column, String message) {
        Object v = r.get(column);
        if (v != null && Objects.equals(((Number) v).longValue(), CurrentUser.id().orElse(null))) throw new BusinessRuleException("OWN_REQUEST", message);
    }

    private Map<String, Object> lockReq(Long id) {
        return jdbc.queryForList("select * from purchase_requisitions where id = ? for update", id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Requisition not found"));
    }

    private Map<String, Object> lockOrder(Long id) {
        return jdbc.queryForList("select * from purchase_orders where id = ? for update", id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    private static LocalDate date(Timestamp t) {
        return t == null ? null : t.toInstant().atZone(TZ).toLocalDate();
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.0f", v);
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String oneOf(String v, Set<String> allowed, String what) {
        String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new BusinessRuleException("FIELD_INVALID", "Choose " + what);
        return u;
    }

    private static String required(String s, int max, String what) {
        String t = cut(s, max);
        if (t == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return t;
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
