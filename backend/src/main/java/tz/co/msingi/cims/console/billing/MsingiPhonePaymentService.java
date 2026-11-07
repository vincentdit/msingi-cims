package tz.co.msingi.cims.console.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.aggregator.AggregatorClient;
import tz.co.msingi.cims.finance.aggregator.AggregatorClients;
import tz.co.msingi.cims.finance.aggregator.AggregatorProvider;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An institution paying a Msingi invoice (an SMS bundle, a licence) by a prompt on a phone, through Msingi's own aggregator
 * account. The payment is recorded against the invoice the moment the aggregator confirms it (by its notification, or when CIMS
 * asks), and an SMS bundle invoice paid in full delivers the bundle at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MsingiPhonePaymentService {

    static final int EXPIRE_MINUTES = 30;

    public record PhoneRequest(String phone, String network) {
    }

    public record View(Long id, Long invoiceId, String invoiceNo, String status, String phone, BigDecimal amount, String provider, String message,
                       String invoiceStatus, OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    public record Outcome(int status, String message) {
    }

    private final JdbcTemplate jdbc;
    private final MsingiCollectionService collection;
    private final AggregatorClients clients;
    private final MsingiBillingService billing;
    private final IntegrationCallRecorder recorder;

    /** The institution's administrator or bursar asks for a prompt on a phone to pay one of its open Msingi invoices. */
    @Transactional
    public View request(Long invoiceId, PhoneRequest req, String who) {
        Long tenantId = TenantContext.requireTenantId();
        AggregatorClient.Account a = collection.active()
                .orElseThrow(() -> new BusinessRuleException("PHONE_PAYMENT_OFF", "Paying by a prompt on your phone is not available yet: pay with the reference."));
        List<Map<String, Object>> inv = jdbc.queryForList("select * from billing_invoices where id = ? and tenant_id = ?", invoiceId, tenantId);
        if (inv.isEmpty()) throw new NotFoundException("Invoice", invoiceId);
        Map<String, Object> i = inv.get(0);
        if (!List.of("ISSUED", "PARTLY_PAID").contains((String) i.get("status"))) {
            throw new BusinessRuleException("NOTHING_TO_PAY", "Nothing is left to pay on this invoice.");
        }
        if (!billing.allows(billing.payKind(invoiceId, (String) i.get("kind")), "MOBILE")) {
            throw new BusinessRuleException("MOBILE_NOT_ALLOWED", "This invoice is paid by bank transfer only: use the accounts shown on the invoice.");
        }
        BigDecimal balance = ((BigDecimal) i.get("total")).subtract((BigDecimal) i.get("paid_amount"));
        String phone = phone(req == null ? null : req.phone());
        String network = req == null || req.network() == null || req.network().isBlank() ? null : req.network().trim().toUpperCase(Locale.ROOT);
        if (a.provider().needsNetwork() && network == null) throw new BusinessRuleException("NETWORK_REQUIRED", "Choose the mobile money service the phone uses.");
        List<Long> recent = jdbc.queryForList("""
                select id from msingi_phone_payments where invoice_id = ? and phone = ? and status = 'PENDING' and created_at > now() - interval '90 seconds'
                order by id desc limit 1""", Long.class, invoiceId, phone);
        if (!recent.isEmpty()) return view(recent.get(0));
        String reference = (String) i.get("payment_reference");
        Integer n = jdbc.queryForObject("select count(*) from msingi_phone_payments where invoice_id = ?", Integer.class, invoiceId);
        String orderId = "M" + reference + "P" + ((n == null ? 0 : n) + 1);
        BigDecimal amount = balance.setScale(0, RoundingMode.CEILING);
        Long id = jdbc.queryForObject("""
                insert into msingi_phone_payments (tenant_id, invoice_id, reference, provider, order_id, phone, amount, requested_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, tenantId, invoiceId, reference, a.provider().name(), orderId, phone, amount, who);
        AggregatorClient.Push push = new AggregatorClient.Push(orderId, reference, phone, network, amount, "Msingi invoice " + i.get("invoice_no"), null, null);
        AggregatorClient.Result r;
        try {
            r = clients.of(a.provider()).push(a, push);
        } catch (RuntimeException e) {
            r = AggregatorClient.Result.of(AggregatorClient.State.FAILED, e.getMessage());
        }
        recorder.record(tenantId, IntegrationCallRecorder.ExternalSystem.COLLECTION, "msingiPhonePrompt " + a.provider().name().toLowerCase(Locale.ROOT), false,
                live(a), r.state() == AggregatorClient.State.FAILED ? IntegrationCallRecorder.Outcome.FAILURE : IntegrationCallRecorder.Outcome.SUCCESS,
                null, "reference " + reference, r.message());
        if (r.state() == AggregatorClient.State.FAILED) {
            jdbc.update("update msingi_phone_payments set status = 'FAILED', message = ?, completed_at = now(), updated_at = now() where id = ?", cut(r.message()), id);
        } else {
            jdbc.update("update msingi_phone_payments set provider_ref = ?, message = ?, updated_at = now() where id = ?", r.providerRef(), cut(r.message()), id);
        }
        return view(id);
    }

    /** The institution's own request; while it waits the aggregator is asked again (at most every few seconds). */
    @Transactional
    public View mine(Long id) {
        Map<String, Object> row = row(id);
        if (!((Number) row.get("tenant_id")).equals(TenantContext.requireTenantId())) throw new NotFoundException("Phone payment", id);
        refresh(row, 3);
        return view(id);
    }

    /** A notification from the aggregator at Msingi's own address. */
    @Transactional
    public Outcome notification(AggregatorProvider provider, String token, Map<String, String> headers, String body) {
        if (!collection.tokenMatches(provider, token)) return new Outcome(404, "Unknown address");
        AggregatorClient.Account a = collection.load().orElseThrow();
        AggregatorClient c = clients.of(provider);
        AggregatorClient.Notification n;
        try {
            n = c.read(a, headers, body);
        } catch (SecurityException e) {
            return new Outcome(401, e.getMessage());
        }
        List<Map<String, Object>> rows = n.orderId() == null ? List.of() : jdbc.queryForList("select * from msingi_phone_payments where order_id = ?", n.orderId());
        if (rows.isEmpty()) return new Outcome(200, billPay(provider, a, c, n));
        AggregatorClient.Result r = n.result();
        if (provider == AggregatorProvider.CLICKPESA) r = c.status(a, n.orderId(), (String) rows.get(0).get("provider_ref"));   // unsigned: ask
        return new Outcome(200, apply(rows.get(0), r, a));
    }

    /**
     * A payment made with an invoice's BillPay control number (Msingi's BillPay-Namba followed by the payment reference), from mobile
     * money, CRDB SimBanking or a CRDB agent: recorded against the invoice like any payment reported by the collector, once.
     */
    private String billPay(AggregatorProvider provider, AggregatorClient.Account a, AggregatorClient c, AggregatorClient.Notification n) {
        String quoted = n.orderId() != null ? n.orderId() : n.reference();
        String ref = MsingiCollectionService.referenceIn(quoted, collection.savedBillPayNamba());
        if (!MsingiBillingService.validReference(ref)) return "Unknown order: ignored";
        AggregatorClient.Result r = n.result();
        if (provider == AggregatorProvider.CLICKPESA) r = c.status(a, quoted, null);   // unsigned: ask ClickPesa
        if (r == null || r.state() != AggregatorClient.State.PAID) return "Not paid yet";
        String tx = r.transactionId() != null ? r.transactionId() : r.providerRef() != null ? r.providerRef() : quoted;
        if (r.amount() == null || r.amount().signum() <= 0) return "No amount: ignored";
        String ch = r.channel() == null ? "" : r.channel().toUpperCase(Locale.ROOT);
        String method = ch.contains("CRDB") || ch.contains("BANK") ? "BANK" : "MOBILE_MONEY";
        var paid = billing.online(ref, provider.name(), tx, r.amount(), r.paidAt() != null ? r.paidAt() : OffsetDateTime.now(), method);
        if (paid == null) return "Unknown reference: ignored";
        recorder.record(paid.tenantId(), IntegrationCallRecorder.ExternalSystem.COLLECTION, "msingiBillPay " + provider.name().toLowerCase(Locale.ROOT), true,
                live(a), IntegrationCallRecorder.Outcome.SUCCESS, null, "reference " + ref, "receipt " + paid.receiptNo());
        return "Payment recorded.";
    }

    /** For the job: ask about prompts still waiting, and close those nobody answered. */
    @Transactional
    public int sweep() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from msingi_phone_payments where status = 'PENDING' and created_at < now() - interval '20 seconds' order by id limit 100""");
        rows.forEach(r -> refresh(r, 20));
        return rows.size();
    }

    private void refresh(Map<String, Object> row, int minSeconds) {
        if (!"PENDING".equals(row.get("status"))) return;
        OffsetDateTime last = time(row.get("last_checked_at"));
        if (last != null && last.isAfter(OffsetDateTime.now().minusSeconds(minSeconds))) return;
        AggregatorClient.Account a = collection.load().orElse(null);
        AggregatorClient.Result r;
        if (a == null || !a.provider().name().equals(row.get("provider"))) {
            r = AggregatorClient.Result.of(AggregatorClient.State.UNKNOWN, "The aggregator was changed");
        } else {
            try {
                r = clients.of(a.provider()).status(a, (String) row.get("order_id"), (String) row.get("provider_ref"));
            } catch (RuntimeException e) {
                r = AggregatorClient.Result.of(AggregatorClient.State.UNKNOWN, e.getMessage());
            }
        }
        apply(row, r, a);
    }

    private String apply(Map<String, Object> row, AggregatorClient.Result r, AggregatorClient.Account a) {
        Long id = ((Number) row.get("id")).longValue();
        if ("PAID".equals(row.get("status"))) return "Already recorded.";
        switch (r.state()) {
            case PAID -> {
                String tx = r.transactionId() != null ? r.transactionId() : r.providerRef() != null ? r.providerRef() : (String) row.get("order_id");
                BigDecimal amount = r.amount() != null && r.amount().signum() > 0 ? r.amount() : (BigDecimal) row.get("amount");
                String provider = (String) row.get("provider");
                billing.online((String) row.get("reference"), provider, tx, amount, r.paidAt() != null ? r.paidAt() : OffsetDateTime.now(), "MOBILE_MONEY");
                jdbc.update("""
                        update msingi_phone_payments set status = 'PAID', transaction_id = ?, channel = ?, message = ?, completed_at = now(), updated_at = now(),
                          checks = checks + 1, last_checked_at = now() where id = ?""", cut40(tx), r.channel() == null ? null : cut40(r.channel()),
                        "Paid" + (r.channel() == null ? "" : " by " + r.channel()), id);
                log.info("Msingi phone payment {} recorded: {} TZS {}", row.get("order_id"), tx, amount);
                return "Payment recorded.";
            }
            case FAILED -> {
                jdbc.update("""
                        update msingi_phone_payments set status = 'FAILED', message = ?, completed_at = now(), updated_at = now(), checks = checks + 1,
                          last_checked_at = now() where id = ? and status = 'PENDING'""", cut(r.message() == null ? "Not paid" : r.message()), id);
                return "Not paid: " + r.message();
            }
            default -> {
                OffsetDateTime created = time(row.get("created_at"));
                if (created != null && created.isBefore(OffsetDateTime.now().minusMinutes(EXPIRE_MINUTES))) {
                    jdbc.update("""
                            update msingi_phone_payments set status = 'EXPIRED', message = ?, completed_at = now(), updated_at = now(), last_checked_at = now()
                            where id = ? and status = 'PENDING'""", "No confirmation within " + EXPIRE_MINUTES + " minutes.", id);
                    return "Expired";
                }
                jdbc.update("update msingi_phone_payments set checks = checks + 1, last_checked_at = now(), message = coalesce(?, message), updated_at = now() where id = ?",
                        cut(r.message()), id);
                return "Still waiting";
            }
        }
    }

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from msingi_phone_payments where id = ?", id);
        if (l.isEmpty()) throw new NotFoundException("Phone payment", id);
        return l.get(0);
    }

    private View view(Long id) {
        return jdbc.query("""
                select p.*, i.invoice_no, i.status as invoice_status from msingi_phone_payments p join billing_invoices i on i.id = p.invoice_id where p.id = ?""",
                (rs, n) -> new View(rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("invoice_no"), rs.getString("status"), rs.getString("phone"),
                        rs.getBigDecimal("amount"), AggregatorProvider.valueOf(rs.getString("provider")).label(), rs.getString("message"),
                        rs.getString("invoice_status"), time(rs.getTimestamp("created_at")), time(rs.getTimestamp("completed_at"))), id).get(0);
    }

    static String phone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) d = "255" + d.substring(1);
        else if (d.length() == 9) d = "255" + d;
        if (!d.matches("255[67]\\d{8}")) throw new BusinessRuleException("INVALID_PHONE", "Enter a Tanzanian mobile number such as 0712 345 678.");
        return d;
    }

    private static boolean live(AggregatorClient.Account a) {
        return a != null && a.live() && a.provider() != AggregatorProvider.SIMULATED;
    }

    private static OffsetDateTime time(Object o) {
        if (o instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        if (o instanceof OffsetDateTime t) return t;
        return null;
    }

    private static String cut(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }

    private static String cut40(String s) {
        return s == null || s.length() <= 40 ? s : s.substring(0, 40);
    }
}
