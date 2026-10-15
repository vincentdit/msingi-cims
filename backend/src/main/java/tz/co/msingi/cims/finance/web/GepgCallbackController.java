package tz.co.msingi.cims.finance.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Element;
import tz.co.msingi.cims.finance.gepg.GepgKeys;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.finance.gepg.GepgXml;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationService;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder.ExternalSystem;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder.Outcome;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Receives GePG's messages. The institution is identified from the bill id CIMS generated ("tenantId-invoiceId-n") or the
 * reconciliation request id ("tenantId-yyyymmdd-n"), so no cross-institution lookup is needed.
 * <ul>
 *   <li>Live GePG (XML): control numbers ({@code gepgBillSubResp}), payments ({@code gepgPmtSpInfo}) and reconciliations
 *       ({@code gepgSpReconcResp}), each signed by GePG (SHA1withRSA) and checked against GePG's certificate; CIMS answers with a
 *       signed acknowledgement, 7101 when received.</li>
 *   <li>Simulated (JSON): a payment notification with an HMAC-SHA256 signature in {@code X-Gepg-Signature}.</li>
 * </ul>
 */
@Slf4j
@Tag(name = "Integrations — GePG")
@RestController
@RequestMapping("/api/integrations/gepg")
@RequiredArgsConstructor
public class GepgCallbackController {

    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String XML = MediaType.APPLICATION_XML_VALUE;
    private static final String TEXT_XML = MediaType.TEXT_XML_VALUE;

    private final BillingService billing;
    private final ReconciliationService reconciliation;
    private final TenantRepository tenants;
    private final GepgProperties props;
    private final ObjectMapper mapper;
    private final IntegrationCallRecorder calls;
    private final ObjectProvider<GepgKeys> keys;

    // ------------------------------------------------------------------ simulated (JSON)

    @Operation(summary = "GePG payment notification (simulated GePG: JSON with an HMAC signature)")
    @PostMapping(value = "/payment-notification", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> paymentNotification(@RequestBody String body,
                                                                   @RequestHeader(name = "X-Gepg-Signature", required = false) String signature) throws Exception {
        long start = System.nanoTime();
        if (!verify(body, signature)) {
            log.warn("Rejected GePG notification with invalid signature");
            inbound(null, "paymentNotification", null, false, start, "Rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "invalid signature"));
        }
        BillingService.PaymentNotification n = mapper.readValue(body, BillingService.PaymentNotification.class);
        Optional<Tenant> tenant = tenantFromId(n.billId());
        if (tenant.isEmpty()) {
            inbound(null, "paymentNotification", n.billId(), false, start, "Rejected: unknown bill");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown bill"));
        }
        try {
            TenantContext.runAs(tenant.get().toInfo(), () -> billing.recordPayment(n));
        } catch (RuntimeException e) {
            inbound(tenant.get().getId(), "paymentNotification", n.billId(), false, start, "Not recorded: " + e.getMessage());
            throw e;
        }
        inbound(tenant.get().getId(), "paymentNotification", n.billId(), true, start, "Payment recorded");
        return ResponseEntity.ok(Map.of("status", "ACCEPTED"));
    }

    // ------------------------------------------------------------------ live GePG (signed XML)

    @Operation(summary = "GePG payment notification (live GePG: signed gepgPmtSpInfo)")
    @PostMapping(value = "/payment-notification", consumes = {XML, TEXT_XML}, produces = XML)
    public ResponseEntity<String> paymentXml(@RequestBody byte[] raw) {
        String body = utf8(raw);   // XML is UTF-8 unless declared otherwise; the signature covers these bytes
        return handle(body, "gepgPmtSpInfo", "paymentNotification", root -> {
            Element t = GepgXml.all(root, "PymtTrxInf").stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No PymtTrxInf"));
            String billId = GepgXml.text(t, "BillId");
            BillingService.PaymentNotification n = new BillingService.PaymentNotification(billId, GepgXml.text(t, "PayCtrNum"),
                    GepgXml.text(t, "PayRefId"), GepgXml.text(t, "PspReceiptNumber"), GepgXml.text(t, "PspName"), GepgXml.text(t, "UsdPayChnl"),
                    amount(GepgXml.text(t, "PaidAmt")), GepgXml.text(t, "PyrCellNum"), time(GepgXml.text(t, "TrxDtTm")));
            return new Work(billId, () -> billing.recordPayment(n) == null
                    ? "Payment " + n.gepgReceiptNo() + " already recorded (sent again)" : "Payment " + n.gepgReceiptNo() + " recorded", true);
        });
    }

    @Operation(summary = "GePG control number for a bill (live GePG: signed gepgBillSubResp)")
    @PostMapping(value = "/control-number", consumes = {XML, TEXT_XML}, produces = XML)
    public ResponseEntity<String> controlNumber(@RequestBody byte[] raw) {
        String body = utf8(raw);   // XML is UTF-8 unless declared otherwise; the signature covers these bytes
        return handle(body, "gepgBillSubResp", "controlNumber", root -> {
            Element t = GepgXml.all(root, "BillTrxInf").stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No BillTrxInf"));
            String billId = GepgXml.text(t, "BillId");
            String cn = GepgXml.text(t, "PayCntrNum");
            String code = GepgXml.text(t, "TrxStsCode");
            boolean issued = "GS".equalsIgnoreCase(GepgXml.text(t, "TrxSts")) && cn != null && cn.matches("\\d{6,20}") && !cn.matches("0+");
            // a bill CIMS does not know yet may still be committing: GePG resends while CIMS answers other than 7101
            return new Work(billId, () -> {
                boolean known = issued ? billing.controlNumberIssued(billId, cn) : billing.controlNumberRefused(billId, code);
                if (!known) throw new IllegalStateException("unknown bill");
                return issued ? "Control number " + cn : "Refused by GePG (" + code + ")";
            }, false);
        });
    }

    @Operation(summary = "GePG reconciliation for a day (live GePG: signed gepgSpReconcResp)")
    @PostMapping(value = "/reconciliation", consumes = {XML, TEXT_XML}, produces = XML)
    public ResponseEntity<String> reconciliationXml(@RequestBody byte[] raw) {
        String body = utf8(raw);   // XML is UTF-8 unless declared otherwise; the signature covers these bytes
        return handle(body, "gepgSpReconcResp", "reconciliation", root -> {
            String requestId = GepgXml.text(root, "SpReconcReqId");
            List<ReconciliationService.GepgTransaction> list = new ArrayList<>();
            for (Element t : GepgXml.all(root, "ReconcTrxInf")) {
                list.add(new ReconciliationService.GepgTransaction(GepgXml.text(t, "PayRefId"), GepgXml.text(t, "SpBillId"), GepgXml.text(t, "BillCtrNum"),
                        GepgXml.text(t, "pspTrxId"), GepgXml.text(t, "PspName"), amount(GepgXml.text(t, "PaidAmt")), time(GepgXml.text(t, "TrxDtTm")),
                        GepgXml.text(t, "UsdPayChnl"), GepgXml.text(t, "DptCellNum")));
            }
            return new Work(requestId, () -> reconciliation.importFromGepg(requestId, list), false);
        });
    }

    /** What to do with a message once its signature is checked: the id that names the institution, and the work in its context. */
    private record Work(String id, Supplier<String> action, boolean ackWhenUnknown) {
    }

    private ResponseEntity<String> handle(String body, String contentTag, String operation, java.util.function.Function<Element, Work> read) {
        long start = System.nanoTime();
        GepgKeys k = keys.getIfAvailable();
        String ackTag = contentTag + "Ack";
        if (k == null) {
            inbound(null, operation, null, false, start, "Rejected: GePG is not in live mode");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN).body("GePG live mode is not configured");
        }
        if (body == null || GepgXml.inner(body, contentTag) == null) {
            inbound(null, operation, null, false, start, "Rejected: not a " + contentTag);
            return xml(k.ack(ackTag, 7242));
        }
        if (!k.fromGepg(body, contentTag)) {
            log.warn("Rejected GePG {} with an invalid signature", contentTag);
            inbound(null, operation, null, false, start, "Rejected: invalid signature");
            return xml(k.ack(ackTag, 7303));
        }
        Work w;
        try {
            w = read.apply(GepgXml.parse(body));
        } catch (RuntimeException e) {
            inbound(null, operation, null, false, start, "Rejected: " + e.getMessage());
            return xml(k.ack(ackTag, 7242));
        }
        Optional<Tenant> tenant = tenantFromId(w.id());
        if (tenant.isEmpty()) {
            inbound(null, operation, w.id(), false, start, "Unknown institution in " + w.id());
            return xml(k.ack(ackTag, w.ackWhenUnknown() ? 7101 : 7201));
        }
        try {
            String detail = TenantContext.callAs(tenant.get().toInfo(), w.action());
            inbound(tenant.get().getId(), operation, w.id(), true, start, detail);
            return xml(k.ack(ackTag, 7101));
        } catch (RuntimeException e) {
            log.warn("GePG {} for {} not processed: {}", contentTag, w.id(), e.getMessage());
            inbound(tenant.get().getId(), operation, w.id(), false, start, "Not processed: " + e.getMessage());
            // a payment for a bill CIMS does not know is acknowledged (resending will not help) and shows up in reconciliation
            boolean unknown = e instanceof tz.co.msingi.cims.common.exception.NotFoundException;
            return xml(k.ack(ackTag, w.ackWhenUnknown() && unknown ? 7101 : 7201));
        }
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(new MediaType("application", "xml", StandardCharsets.UTF_8)).body(body);
    }

    private static String utf8(byte[] raw) {
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private void inbound(Long tenantId, String operation, String id, boolean ok, long start, String detail) {
        calls.record(tenantId, ExternalSystem.GEPG, operation, true, !props.isSimulated(), ok ? Outcome.SUCCESS : Outcome.FAILURE,
                (System.nanoTime() - start) / 1_000_000, id == null ? null : "bill " + id, detail == null || detail.length() <= 480 ? detail : detail.substring(0, 480));
    }

    private Optional<Tenant> tenantFromId(String id) {
        if (id == null || !id.contains("-")) {
            return Optional.empty();
        }
        try {
            return tenants.findById(Long.parseLong(id.substring(0, id.indexOf('-'))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal amount(String s) {
        if (s == null) throw new IllegalArgumentException("No amount");
        return new BigDecimal(s.trim());
    }

    /** GePG's times are East Africa Time without an offset. */
    private static OffsetDateTime time(String s) {
        if (s == null || s.isBlank()) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (Exception e) {
            return LocalDateTime.parse(s.trim().replace(' ', 'T')).atZone(EAT).toOffsetDateTime();
        }
    }

    private boolean verify(String body, String signature) throws Exception {
        if (!props.isSimulated() || signature == null) {
            return false;   // live GePG sends signed XML, handled above
        }
        String expected = sign(body, props.callbackSecret());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
