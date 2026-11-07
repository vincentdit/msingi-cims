package tz.co.msingi.cims.console.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Services Msingi sells besides the subscription (console › Billing › Services): Msingi defines each with its price, quantities,
 * VAT, payment method and how it is delivered. An institution requests one (Administration › Msingi subscription › Services): the
 * invoice is issued at once with its payment reference; when paid, an SMS pack is credited automatically and any other service is
 * handed to Msingi's staff to deliver and mark delivered.
 */
@Service
@RequiredArgsConstructor
public class MsingiServicesService {

    public record ServiceView(Long id, String code, String name, String description, String unitLabel, BigDecimal unitPrice, int minQuantity,
                              int maxQuantity, BigDecimal vatPercent, String payMethods, String delivery, Integer smsPerUnit, boolean active,
                              String updatedByName, OffsetDateTime updatedAt) {
    }

    public record ServiceInput(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                               @Size(max = 40) String unitLabel, BigDecimal unitPrice, Integer minQuantity, Integer maxQuantity, BigDecimal vatPercent,
                               String payMethods, String delivery, Integer smsPerUnit, Boolean active) {
    }

    public record OrderView(Long id, Long tenantId, String institution, Long serviceId, String service, String unitLabel, int quantity, Long invoiceId,
                            String invoiceNo, String paymentReference, BigDecimal total, String invoiceStatus, String status, String delivery, String note,
                            String requestedByName, OffsetDateTime requestedAt, OffsetDateTime paidAt, OffsetDateTime deliveredAt,
                            String deliveredByName, String deliveryNote) {
    }

    public record OrderRequest(Long serviceId, Integer quantity, @Size(max = 500) String note) {
    }

    public record Delivered(@Size(max = 500) String note) {
    }

    private static final RowMapper<ServiceView> SERVICE = (rs, i) -> new ServiceView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getString("unit_label"), rs.getBigDecimal("unit_price"), rs.getInt("min_quantity"), rs.getInt("max_quantity"),
            rs.getBigDecimal("vat_percent"), rs.getString("pay_methods"), rs.getString("delivery"), (Integer) rs.getObject("sms_per_unit"),
            rs.getBoolean("active"), rs.getString("updated_by_name"), rs.getObject("updated_at", OffsetDateTime.class));

    private static final String ORDER = """
            select o.*, t.name as institution, s.name as service, s.unit_label, s.delivery, i.invoice_no, i.payment_reference, i.total, i.status as invoice_status
            from billing_service_orders o join billing_services s on s.id = o.service_id join billing_invoices i on i.id = o.invoice_id
            join tenants t on t.id = o.tenant_id""";
    private static final RowMapper<OrderView> ORDER_ROW = (rs, i) -> new OrderView(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("institution"),
            rs.getLong("service_id"), rs.getString("service"), rs.getString("unit_label"), rs.getInt("quantity"), rs.getLong("invoice_id"),
            rs.getString("invoice_no"), rs.getString("payment_reference"), rs.getBigDecimal("total"), rs.getString("invoice_status"), rs.getString("status"),
            rs.getString("delivery"), rs.getString("note"), rs.getString("requested_by_name"), rs.getObject("requested_at", OffsetDateTime.class),
            rs.getObject("paid_at", OffsetDateTime.class), rs.getObject("delivered_at", OffsetDateTime.class), rs.getString("delivered_by_name"),
            rs.getString("delivery_note"));

    private final JdbcTemplate jdbc;
    private final MsingiBillingService billing;
    private final PlatformAuditService audit;

    // ------------------------------------------------------------------ Msingi

    @Transactional(readOnly = true)
    public List<ServiceView> all() {
        return jdbc.query("select * from billing_services order by active desc, name", SERVICE);
    }

    @Transactional
    public ServiceView save(Long id, ServiceInput in) {
        String code = in.code().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (in.unitPrice() == null || in.unitPrice().signum() <= 0) throw new BusinessRuleException("SERVICE_PRICE", "Give the price of one " + unit(in) + ".");
        int min = in.minQuantity() == null ? 1 : in.minQuantity(), max = in.maxQuantity() == null ? Math.max(min, 1) : in.maxQuantity();
        if (min < 1 || max < min || max > 1_000_000) throw new BusinessRuleException("SERVICE_QUANTITY", "The quantities run from at least 1 up to a maximum no smaller than the minimum.");
        BigDecimal vat = in.vatPercent() == null ? BigDecimal.valueOf(18) : in.vatPercent();
        if (vat.signum() < 0 || vat.compareTo(BigDecimal.valueOf(50)) > 0) throw new BusinessRuleException("SERVICE_VAT", "VAT must be between 0 and 50 per cent.");
        String pay = in.payMethods() == null ? "BOTH" : in.payMethods().trim().toUpperCase(Locale.ROOT);
        if (!MsingiBillingService.PAY_METHODS.contains(pay)) throw new BusinessRuleException("PAY_METHOD", "Choose mobile money, bank or both.");
        String delivery = in.delivery() == null ? "MSINGI_DELIVERS" : in.delivery().trim().toUpperCase(Locale.ROOT);
        if (!List.of("SMS_CREDIT", "MSINGI_DELIVERS").contains(delivery)) throw new BusinessRuleException("SERVICE_DELIVERY", "Choose how the service is delivered.");
        Integer sms = "SMS_CREDIT".equals(delivery) ? in.smsPerUnit() : null;
        if ("SMS_CREDIT".equals(delivery) && (sms == null || sms < 1)) throw new BusinessRuleException("SERVICE_SMS", "Say how many SMS each " + unit(in) + " adds.");
        boolean active = in.active() == null || in.active();
        String label = in.unitLabel() == null || in.unitLabel().isBlank() ? "unit" : in.unitLabel().trim();
        Integer clash = jdbc.queryForObject("select count(*) from billing_services where code = ? and id <> coalesce(?, -1)", Integer.class, code, id);
        if (clash != null && clash > 0) throw new BusinessRuleException("SERVICE_CODE", "Another service already has the code " + code + ".");
        Long saved;
        if (id == null) {
            saved = jdbc.queryForObject("""
                    insert into billing_services (code, name, description, unit_label, unit_price, min_quantity, max_quantity, vat_percent, pay_methods,
                      delivery, sms_per_unit, active, updated_by_name) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                    code, in.name().trim(), blank(in.description()), label, in.unitPrice(), min, max, vat, pay, delivery, sms, active, CurrentUser.platformName());
        } else {
            int n = jdbc.update("""
                    update billing_services set code = ?, name = ?, description = ?, unit_label = ?, unit_price = ?, min_quantity = ?, max_quantity = ?,
                      vat_percent = ?, pay_methods = ?, delivery = ?, sms_per_unit = ?, active = ?, updated_by_name = ?, updated_at = now() where id = ?""",
                    code, in.name().trim(), blank(in.description()), label, in.unitPrice(), min, max, vat, pay, delivery, sms, active, CurrentUser.platformName(), id);
            if (n == 0) throw new NotFoundException("Service", id);
            saved = id;
        }
        audit.record(id == null ? "MSINGI_SERVICE_CREATED" : "MSINGI_SERVICE_UPDATED", null, "BillingService", saved,
                Map.of("code", code, "price", in.unitPrice().toPlainString(), "active", active));
        return jdbc.queryForObject("select * from billing_services where id = ?", SERVICE, saved);
    }

    @Transactional(readOnly = true)
    public List<OrderView> orders(String status) {
        String where = status == null || status.isBlank() || "all".equalsIgnoreCase(status) ? "" : " where o.status = ?";
        Object[] args = where.isEmpty() ? new Object[0] : new Object[]{status.trim().toUpperCase(Locale.ROOT)};
        return jdbc.query(ORDER + where + " order by case o.status when 'PAID' then 0 when 'REQUESTED' then 1 else 2 end, o.id desc limit 200", ORDER_ROW, args);
    }

    /** Msingi has delivered a paid service (a training day held, data migrated …). */
    @Transactional
    public OrderView markDelivered(Long orderId, Delivered d) {
        int n = jdbc.update("""
                update billing_service_orders set status = 'DELIVERED', delivered_at = now(), delivered_by_name = ?, delivery_note = ?
                where id = ? and status = 'PAID'""", CurrentUser.platformName(), d == null ? null : blank(d.note()), orderId);
        if (n == 0) throw new BusinessRuleException("NOT_PAID", "Only a paid service can be marked delivered.");
        OrderView o = jdbc.queryForObject(ORDER + " where o.id = ?", ORDER_ROW, orderId);
        audit.record("SERVICE_ORDER_DELIVERED", o.tenantId(), "BillingServiceOrder", orderId, Map.of("service", o.service()));
        return o;
    }

    // ------------------------------------------------------------------ institution

    @Transactional(readOnly = true)
    public List<ServiceView> catalogue() {
        return jdbc.query("select * from billing_services where active order by name", SERVICE);
    }

    @Transactional(readOnly = true)
    public List<OrderView> mine() {
        return jdbc.query(ORDER + " where o.tenant_id = ? order by o.id desc", ORDER_ROW, TenantContext.requireTenantId());
    }

    /** The institution requests a service: its invoice is issued at once with its payment reference. */
    @Transactional
    public BillingModels.InvoiceView request(OrderRequest req, String by) {
        Long tenantId = TenantContext.requireTenantId();
        if (req == null || req.serviceId() == null) throw new BusinessRuleException("SERVICE_REQUIRED", "Choose the service.");
        List<ServiceView> s = jdbc.query("select * from billing_services where id = ? and active", SERVICE, req.serviceId());
        if (s.isEmpty()) throw new NotFoundException("Service", req.serviceId());
        ServiceView v = s.get(0);
        int qty = req.quantity() == null ? v.minQuantity() : req.quantity();
        if (qty < v.minQuantity() || qty > v.maxQuantity()) {
            throw new BusinessRuleException("SERVICE_QUANTITY", v.minQuantity() == v.maxQuantity() ? "This service is bought " + v.minQuantity() + " at a time."
                    : "Choose between " + v.minQuantity() + " and " + v.maxQuantity() + " " + v.unitLabel() + "(s).");
        }
        Integer open = jdbc.queryForObject("select count(*) from billing_service_orders where tenant_id = ? and service_id = ? and status = 'REQUESTED'",
                Integer.class, tenantId, v.id());
        if (open != null && open >= 2) throw new BusinessRuleException("SERVICE_WAITING", "Two requests for this service are already waiting for payment: pay one first.");
        String description = v.name() + (qty > 1 || !"unit".equals(v.unitLabel()) ? String.format(Locale.ENGLISH, " (%,d %s)", qty, v.unitLabel()) : "");
        BillingModels.InvoiceView inv = billing.issueServiceInvoice(tenantId, description, qty, v.unitPrice(), v.vatPercent(), v.payMethods(), by,
                blank(req.note()) == null ? "Delivered as soon as this invoice is paid." : "Your note: " + req.note().trim());
        jdbc.update("insert into billing_service_orders (tenant_id, service_id, quantity, invoice_id, note, requested_by_name) values (?, ?, ?, ?, ?, ?)",
                tenantId, v.id(), qty, inv.invoice().id(), blank(req.note()), by);
        return inv;
    }

    private static String unit(ServiceInput in) {
        return in.unitLabel() == null || in.unitLabel().isBlank() ? "unit" : in.unitLabel().trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
