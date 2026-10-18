package tz.co.msingi.cims.console.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.billing.BillingModels.PaymentRow;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Payments to Msingi reported by Msingi's own payment collector (an aggregator, a mobile money operator or the bank). The payer
 * quoted the invoice's 12-digit payment reference (starting with 7). The body is signed with HMAC-SHA256 (header X-Billing-Signature)
 * using cims.billing.callback-secret. A repeated notification (same provider and transaction id) is acknowledged without changes.
 */
@Slf4j
@Tag(name = "Integrations — Msingi billing payments")
@RestController
@RequestMapping("/api/integrations/msingi-billing")
@RequiredArgsConstructor
public class BillingCallbackController {

    private final MsingiBillingService billing;
    private final ObjectMapper mapper;
    private final IntegrationCallRecorder calls;

    @Value("${cims.billing.callback-secret:change-me}")
    private String secret;

    public record Notification(String reference, String transactionId, String provider, BigDecimal amount, OffsetDateTime paidAt) {
    }

    @Operation(summary = "Payment to Msingi reported by Msingi's payment collector")
    @PostMapping("/payment-notification")
    public ResponseEntity<Map<String, String>> notify(@RequestBody String body,
                                                      @RequestHeader(name = "X-Billing-Signature", required = false) String signature) throws Exception {
        long start = System.nanoTime();
        if (signature == null || !MessageDigest.isEqual(GepgCallbackController.sign(body, secret).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected Msingi billing notification with invalid signature");
            record(null, null, false, start, "Rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "invalid signature"));
        }
        Notification n = mapper.readValue(body, Notification.class);
        if (n.transactionId() == null || n.transactionId().isBlank() || n.reference() == null) {
            record(null, n.reference(), false, start, "Rejected: transaction id and reference are required");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "transactionId and reference are required"));
        }
        PaymentRow p = billing.online(n.reference(), n.provider(), n.transactionId(), n.amount(), n.paidAt());
        if (p == null) {
            record(null, n.reference(), false, start, "Rejected: unknown reference");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown reference"));
        }
        record(p.tenantId(), n.reference(), true, start, "Msingi payment recorded as " + p.receiptNo());
        return ResponseEntity.ok(Map.of("status", "ACCEPTED", "receiptNo", p.receiptNo()));
    }

    private void record(Long tenantId, String reference, boolean ok, long start, String detail) {
        calls.record(tenantId, IntegrationCallRecorder.ExternalSystem.COLLECTION, "msingiBillingPayment", true, true,
                ok ? IntegrationCallRecorder.Outcome.SUCCESS : IntegrationCallRecorder.Outcome.FAILURE,
                (System.nanoTime() - start) / 1_000_000, reference == null ? null : "reference " + reference, detail);
    }
}
