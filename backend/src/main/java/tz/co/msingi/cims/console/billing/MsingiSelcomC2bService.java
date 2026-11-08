package tz.co.msingi.cims.console.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.finance.aggregator.AggregatorProvider;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selcom C2B for Msingi's invoices: an institution pays with "Lipa kwa Selcom Pay" from any mobile wallet, a bank or a Selcom agent,
 * entering Msingi's merchant number and the invoice's payment reference. Selcom then calls CIMS: lookup (is the reference valid,
 * whose is it, how much), validation (may this amount be paid) and notification (it was paid). Each call carries
 * "Authorization: Bearer" with the secret Msingi gave Selcom. The payment is recorded once (by Selcom's transid) and what it pays
 * for is delivered at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MsingiSelcomC2bService {

    private static final Set<String> WALLETS = Set.of("AIRTELMONEY", "MPESA-TZ", "TIGOPESATZ", "HALOPESATZ", "TTCLMOBILE", "ZANTELEZPESA");

    private final JdbcTemplate jdbc;
    private final MsingiCollectionService collection;
    private final MsingiBillingService billing;
    private final IntegrationCallRecorder recorder;

    /** Whether the bearer token is Msingi's secret for its Selcom account. */
    public boolean authorised(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return collection.tokenMatches(AggregatorProvider.SELCOM, authorization.substring(7).trim());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookup(Map<String, Object> req) {
        Map<String, Object> inv = open(req);
        if (inv == null) return answer(req, "010", "Invalid payment reference", null, null);
        // an SMS bundle or a service is delivered whole, so Selcom is told the exact amount; a licence or usage invoice may be paid in parts
        return answer(req, "000", "Invoice " + inv.get("invoice_no"), inv.get("tenant_name") + " — Msingi invoice " + inv.get("invoice_no"),
                wholeOnly(inv) ? balance(inv) : null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validation(Map<String, Object> req) {
        Map<String, Object> inv = open(req);
        if (inv == null) return answer(req, "010", "Invalid payment reference", null, null);
        BigDecimal amount = amount(req);
        if (amount == null || amount.signum() <= 0) return answer(req, "012", "Invalid amount", null, null);
        if (wholeOnly(inv) && amount.compareTo(balance(inv)) < 0) {
            return answer(req, "015", "Amount too low: pay TZS " + balance(inv).stripTrailingZeros().toPlainString() + " in full", null, null);
        }
        return answer(req, "000", "Accepted", (String) inv.get("tenant_name"), null);   // more than due is kept as credit
    }

    /** The payment was made: recorded against the invoice (once), receipted, and what it pays for delivered. */
    @Transactional
    public Map<String, Object> notification(Map<String, Object> req) {
        String ref = reference(req);
        BigDecimal amount = amount(req);
        String transid = text(req, "transid");
        if (!MsingiBillingService.validReference(ref) || amount == null || amount.signum() <= 0 || transid == null) {
            return answer(req, "010", "Invalid payment reference or amount", null, null);
        }
        String operator = text(req, "operator") == null ? "" : text(req, "operator").toUpperCase(Locale.ROOT);
        BillingModels.PaymentRow p = billing.online(ref, "SELCOM", transid, amount, OffsetDateTime.now(), WALLETS.contains(operator) ? "MOBILE_MONEY" : "BANK");
        if (p == null) return answer(req, "010", "Invalid payment reference", null, null);
        recorder.record(p.tenantId(), IntegrationCallRecorder.ExternalSystem.COLLECTION, "msingiSelcomC2b", true, true, IntegrationCallRecorder.Outcome.SUCCESS,
                null, "reference " + ref, "receipt " + p.receiptNo() + (operator.isEmpty() ? "" : " via " + operator));
        log.info("Selcom C2B payment {} for Msingi reference {}: receipt {}", transid, ref, p.receiptNo());
        return answer(req, "000", "Payment received: receipt " + p.receiptNo(), null, null);
    }

    private Map<String, Object> open(Map<String, Object> req) {
        String ref = reference(req);
        if (!MsingiBillingService.validReference(ref)) return null;
        List<Map<String, Object>> r = jdbc.queryForList("""
                select i.invoice_no, i.kind, i.total, i.paid_amount, t.name as tenant_name from billing_invoices i join tenants t on t.id = i.tenant_id
                where i.payment_reference = ? and i.status in ('ISSUED', 'PARTLY_PAID')""", ref);
        return r.isEmpty() ? null : r.get(0);
    }

    private static boolean wholeOnly(Map<String, Object> inv) {
        return "SMS".equals(inv.get("kind")) || "SERVICE".equals(inv.get("kind"));
    }

    private static BigDecimal balance(Map<String, Object> inv) {
        return ((BigDecimal) inv.get("total")).subtract((BigDecimal) inv.get("paid_amount"));
    }

    /** The reference the payer typed (utilityref), spaces and dashes removed; a BillPay-style prefix is ignored. */
    private static String reference(Map<String, Object> req) {
        String v = text(req, "utilityref");
        String d = v == null ? "" : v.replaceAll("\\D", "");
        return d.length() > 12 ? d.substring(d.length() - 12) : d;
    }

    private static BigDecimal amount(Map<String, Object> req) {
        Object a = req.get("amount");
        if (a == null) return null;
        try {
            return new BigDecimal(a.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Map<String, Object> req, String k) {
        Object v = req.get(k);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static Map<String, Object> answer(Map<String, Object> req, String code, String message, String name, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", req.get("reference"));
        m.put("resultcode", code);
        m.put("result", "000".equals(code) ? "SUCCESS" : "FAIL");
        m.put("message", message);
        if (name != null) m.put("name", name);
        if (amount != null) m.put("amount", amount.stripTrailingZeros().toPlainString());
        return m;
    }
}
