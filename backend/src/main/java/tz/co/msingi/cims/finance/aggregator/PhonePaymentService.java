package tz.co.msingi.cims.finance.aggregator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paying an invoice by a prompt on the payer's phone, through the institution's aggregator: CIMS asks the aggregator to prompt the
 * phone for the invoice's balance, the payer enters their PIN, and the payment is recorded against the invoice's payment reference
 * once the aggregator confirms it (by notification, or when CIMS asks). A prompt is never recorded twice; a payer who ignores it
 * can still pay with the reference as before, and the statement import still catches anything the aggregator did not report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhonePaymentService {

    static final int EXPIRE_MINUTES = 30;
    static final int REUSE_SECONDS = 90;

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final BillingService billing;
    private final CollectionAccountService settings;
    private final AggregatorClients clients;
    private final IntegrationCallRecorder recorder;

    public record PhoneRequest(String phone, String network) {
    }

    public record View(Long id, Long invoiceId, String invoiceNo, String payerName, String status, String phone, BigDecimal amount, String provider,
                       String message, String transactionId, String channel, String receiptNo, String requestedBy, OffsetDateTime createdAt,
                       OffsetDateTime completedAt) {
    }

    // ------------------------------------------------------------------ the payer

    @Transactional
    public View requestMine(Long invoiceId, PhoneRequest req, String who) {
        Invoice inv = accounts.requireMine(invoiceId);
        AggregatorClient.Account a = settings.active().filter(x -> settings.phonePrompt().enabled())
                .orElseThrow(() -> new BusinessRuleException("PHONE_PAYMENT_OFF", "Paying by a prompt on your phone is not available here: pay with the reference."));
        if (billing.issuerFor(inv) != ControlNumber.Issuer.COLLECTION) {
            throw new BusinessRuleException("GEPG_PAYMENT", "This invoice is paid through GePG with its control number.");
        }
        BigDecimal balance = inv.balance();
        if (balance == null || balance.signum() <= 0) throw new BusinessRuleException("NOTHING_TO_PAY", "Nothing is left to pay on this invoice.");
        String phone = phone(req == null ? null : req.phone());
        String network = req == null || req.network() == null || req.network().isBlank() ? null : req.network().trim().toUpperCase(Locale.ROOT);
        if (a.provider().needsNetwork() && network == null) {
            throw new BusinessRuleException("NETWORK_REQUIRED", "Choose the mobile money service the phone uses.");
        }
        List<Long> recent = jdbc.queryForList("""
                select id from phone_payment_requests where invoice_id = ? and phone = ? and status = 'PENDING'
                  and created_at > now() - make_interval(secs => ?) order by id desc limit 1""", Long.class, invoiceId, phone, REUSE_SECONDS);
        if (!recent.isEmpty()) return view(recent.get(0));

        ControlNumber cn = billing.ensureControlNumber(inv.getId());
        String reference = cn.getControlNo();
        Integer n = jdbc.queryForObject("select count(*) from phone_payment_requests where invoice_id = ?", Integer.class, invoiceId);
        String orderId = reference + "P" + ((n == null ? 0 : n) + 1);
        BigDecimal amount = balance.setScale(0, RoundingMode.CEILING);
        Long id = jdbc.queryForObject("""
                insert into phone_payment_requests (tenant_id, invoice_id, reference, provider, order_id, phone, amount, requested_by)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                invoiceId, reference, a.provider().name(), orderId, phone, amount, who);
        AggregatorClient.Push push = new AggregatorClient.Push(orderId, reference, phone, network, amount, inv.getDescription(), inv.getPayerName(), inv.getPayerEmail());
        long start = System.nanoTime();
        AggregatorClient.Result r;
        try {
            r = clients.of(a.provider()).push(a, push);
        } catch (RuntimeException e) {
            r = AggregatorClient.Result.of(AggregatorClient.State.FAILED, e.getMessage());
        }
        recorder.record(IntegrationCallRecorder.ExternalSystem.COLLECTION, "phonePrompt " + a.provider().name().toLowerCase(Locale.ROOT), false, live(a),
                r.state() == AggregatorClient.State.FAILED ? IntegrationCallRecorder.Outcome.FAILURE : IntegrationCallRecorder.Outcome.SUCCESS,
                (System.nanoTime() - start) / 1_000_000, "reference " + reference, r.message());
        if (r.state() == AggregatorClient.State.FAILED) {
            jdbc.update("update phone_payment_requests set status = 'FAILED', message = ?, completed_at = now(), updated_at = now() where id = ?", cut(r.message()), id);
        } else {
            jdbc.update("update phone_payment_requests set provider_ref = ?, message = ?, updated_at = now() where id = ?", r.providerRef(), cut(r.message()), id);
        }
        return view(id);
    }

    /** One of the payer's own requests; while it waits, the aggregator is asked again (at most every few seconds). */
    @Transactional
    public View mine(Long id) {
        Map<String, Object> row = row(id);
        accounts.requireMine(((Number) row.get("invoice_id")).longValue());
        maybeRefresh(row, 3);
        return view(id);
    }

    // ------------------------------------------------------------------ the bursary

    @Transactional(readOnly = true)
    public List<View> list(String status) {
        String filter = status == null || status.isBlank() ? "" : " where r.status = '" + status.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "") + "'";
        return jdbc.query(VIEW_SQL + filter + " order by r.id desc limit 200", (rs, i) -> map(rs));
    }

    /** The Accountant asks the aggregator again about a request that is still waiting. */
    @Transactional
    public View checkNow(Long id) {
        maybeRefresh(row(id), 0);
        return view(id);
    }

    // ------------------------------------------------------------------ the aggregator

    public record Outcome(int status, String message) {
    }

    /** A notification from the aggregator, at the institution's own notification address. */
    @Transactional
    public Outcome notification(AggregatorProvider provider, String token, Map<String, String> headers, String body) {
        long start = System.nanoTime();
        if (!settings.tokenMatches(provider, token)) {
            recorder.record(IntegrationCallRecorder.ExternalSystem.COLLECTION, "notification " + provider.name().toLowerCase(Locale.ROOT), true, true,
                    IntegrationCallRecorder.Outcome.FAILURE, null, null, "Rejected: unknown notification address");
            return new Outcome(404, "Unknown address");
        }
        AggregatorClient.Account a = settings.load().orElseThrow();
        AggregatorClient c = clients.of(provider);
        AggregatorClient.Notification n;
        try {
            n = c.read(a, headers, body);
        } catch (SecurityException e) {
            recorder.record(IntegrationCallRecorder.ExternalSystem.COLLECTION, "notification " + provider.name().toLowerCase(Locale.ROOT), true, live(a),
                    IntegrationCallRecorder.Outcome.FAILURE, null, null, "Rejected: " + e.getMessage());
            return new Outcome(401, e.getMessage());
        }
        List<Map<String, Object>> rows = n.orderId() == null ? List.of() : jdbc.queryForList("select * from phone_payment_requests where order_id = ?", n.orderId());
        if (rows.isEmpty()) {
            recorder.record(IntegrationCallRecorder.ExternalSystem.COLLECTION, "notification " + provider.name().toLowerCase(Locale.ROOT), true, live(a),
                    IntegrationCallRecorder.Outcome.FAILURE, (System.nanoTime() - start) / 1_000_000, n.orderId(), "Unknown order: nothing recorded");
            return new Outcome(200, "Unknown order: ignored");
        }
        Map<String, Object> row = rows.get(0);
        AggregatorClient.Result r = n.result();
        if (provider == AggregatorProvider.CLICKPESA) {
            r = c.status(a, n.orderId(), (String) row.get("provider_ref"));   // ClickPesa does not sign: believe only its own answer
        }
        String outcome = apply(row, r, a);
        recorder.record(IntegrationCallRecorder.ExternalSystem.COLLECTION, "notification " + provider.name().toLowerCase(Locale.ROOT), true, live(a),
                IntegrationCallRecorder.Outcome.SUCCESS, (System.nanoTime() - start) / 1_000_000, "reference " + row.get("reference"), outcome);
        return new Outcome(200, outcome);
    }

    /** For the job: ask about waiting requests, and give up on those nobody answered. */
    @Transactional
    public int sweep() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from phone_payment_requests where status = 'PENDING' and created_at < now() - interval '20 seconds'
                order by id limit 100""");
        rows.forEach(r -> maybeRefresh(r, 20));
        return rows.size();
    }

    // ------------------------------------------------------------------ helpers

    private void maybeRefresh(Map<String, Object> row, int minSecondsBetween) {
        if (!"PENDING".equals(row.get("status"))) return;
        OffsetDateTime last = time(row.get("last_checked_at"));
        if (last != null && last.isAfter(OffsetDateTime.now().minusSeconds(minSecondsBetween))) return;
        AggregatorClient.Account a = settings.load().orElse(null);
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

    /** What the aggregator said about a request: paid (recorded once), failed, or still waiting (until it expires). */
    private String apply(Map<String, Object> row, AggregatorClient.Result r, AggregatorClient.Account a) {
        Long id = ((Number) row.get("id")).longValue();
        if ("PAID".equals(row.get("status"))) return "Already recorded.";
        switch (r.state()) {
            case PAID -> {
                return record(row, r, a);
            }
            case FAILED -> {
                jdbc.update("update phone_payment_requests set status = 'FAILED', message = ?, completed_at = now(), updated_at = now(), checks = checks + 1, last_checked_at = now() where id = ? and status = 'PENDING'",
                        cut(r.message() == null ? "Not paid" : r.message()), id);
                return "Not paid: " + r.message();
            }
            default -> {
                OffsetDateTime created = time(row.get("created_at"));
                if (created != null && created.isBefore(OffsetDateTime.now().minusMinutes(EXPIRE_MINUTES))) {
                    jdbc.update("update phone_payment_requests set status = 'EXPIRED', message = ?, completed_at = now(), updated_at = now(), last_checked_at = now() where id = ? and status = 'PENDING'",
                            "No confirmation within " + EXPIRE_MINUTES + " minutes. If the payer was charged, the payment is found in the statement.", id);
                    return "Expired";
                }
                jdbc.update("update phone_payment_requests set checks = checks + 1, last_checked_at = now(), message = coalesce(?, message), updated_at = now() where id = ?",
                        cut(r.message()), id);
                return "Still waiting";
            }
        }
    }

    private String record(Map<String, Object> row, AggregatorClient.Result r, AggregatorClient.Account a) {
        Long id = ((Number) row.get("id")).longValue();
        String provider = (String) row.get("provider");
        String tx = r.transactionId() != null ? r.transactionId() : r.providerRef() != null ? r.providerRef() : (String) row.get("order_id");
        String key = provider + "-" + tx;
        if (key.length() > 40) key = key.substring(0, 40);
        BigDecimal amount = r.amount() != null && r.amount().signum() > 0 ? r.amount() : (BigDecimal) row.get("amount");
        String reference = (String) row.get("reference");
        String billId = jdbc.queryForList("select bill_id from control_numbers where control_no = ? order by id desc limit 1", String.class, reference)
                .stream().findFirst().orElse(null);
        String label = AggregatorProvider.valueOf(provider).label();
        String psp = r.channel() == null ? label : label + " · " + r.channel();
        billing.recordPayment(new BillingService.PaymentNotification(billId, reference, key, cut40(tx), psp.length() > 60 ? psp.substring(0, 60) : psp, "MOBILE",
                amount, r.phone() != null ? r.phone() : (String) row.get("phone"), r.paidAt() != null ? r.paidAt() : OffsetDateTime.now()));
        jdbc.update("""
                update phone_payment_requests set status = 'PAID', transaction_id = ?, channel = ?, message = ?, completed_at = now(), updated_at = now(),
                  checks = checks + 1, last_checked_at = now() where id = ?""", cut40(tx), r.channel() == null ? null : cut40(r.channel()),
                "Paid" + (r.channel() == null ? "" : " by " + r.channel()), id);
        log.info("Phone payment {} recorded: {} TZS {} ({})", row.get("order_id"), key, amount, a == null ? provider : a.provider());
        return "Payment recorded.";
    }

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from phone_payment_requests where id = ?", id);
        if (l.isEmpty()) throw new NotFoundException("Phone payment", id);
        return l.get(0);
    }

    private static final String VIEW_SQL = """
            select r.*, i.invoice_no, i.payer_name, p.receipt_no from phone_payment_requests r join invoices i on i.id = r.invoice_id
            left join payments p on p.gepg_receipt_no = left(r.provider || '-' || r.transaction_id, 40)""";

    private View view(Long id) {
        return jdbc.query(VIEW_SQL + " where r.id = ?", (rs, i) -> map(rs), id).get(0);
    }

    private static View map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new View(rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("invoice_no"), rs.getString("payer_name"), rs.getString("status"),
                rs.getString("phone"), rs.getBigDecimal("amount"), AggregatorProvider.valueOf(rs.getString("provider")).label(), rs.getString("message"),
                rs.getString("transaction_id"), rs.getString("channel"), rs.getString("receipt_no"), rs.getString("requested_by"),
                time(rs.getTimestamp("created_at")), time(rs.getTimestamp("completed_at")));
    }

    /** 255XXXXXXXXX from 07…, 06…, +255…, 255… or nine digits. */
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
