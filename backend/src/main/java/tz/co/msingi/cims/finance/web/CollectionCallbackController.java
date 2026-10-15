package tz.co.msingi.cims.finance.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.collection.CollectionProperties;
import tz.co.msingi.cims.finance.collection.PaymentProvider;
import tz.co.msingi.cims.finance.collection.PaymentReference;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Payment notifications for private and faith-based institutions, from their payment collector (an aggregator, a mobile money
 * operator or a bank collection service). The payer quoted the CIMS payment reference, which carries the institution; a bill id is
 * used instead when the collector echoes it. The body is signed with HMAC-SHA256 (header X-Collection-Signature) using the shared
 * secret cims.collection.callback-secret. A repeated notification (same provider and transaction id) is acknowledged without changes.
 */
@Slf4j
@Tag(name = "Integrations — payment collection")
@RestController
@RequestMapping("/api/integrations/collections")
@RequiredArgsConstructor
public class CollectionCallbackController {

    private final BillingService billing;
    private final TenantRepository tenants;
    private final CollectionProperties props;
    private final ObjectMapper mapper;
    private final IntegrationCallRecorder calls;

    /** provider: a PaymentProvider code (MPESA, MIXX_BY_YAS, CRDB, …) or a free name. */
    public record Notification(String reference, String billId, String transactionId, String provider, BigDecimal amount,
                               String payerPhone, OffsetDateTime paidAt) {
    }

    @Operation(summary = "Payment notification from the institution's payment collector")
    @PostMapping("/payment-notification")
    public ResponseEntity<Map<String, String>> paymentNotification(@RequestBody String body,
                                                                   @RequestHeader(name = "X-Collection-Signature", required = false) String signature) throws Exception {
        long start = System.nanoTime();
        if (signature == null || !MessageDigest.isEqual(GepgCallbackController.sign(body, props.callbackSecret()).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected collection notification with invalid signature");
            record(null, null, false, start, "Rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "invalid signature"));
        }
        Notification n = mapper.readValue(body, Notification.class);
        if (n.transactionId() == null || n.transactionId().isBlank() || (n.reference() == null && n.billId() == null)) {
            record(null, n.reference(), false, start, "Rejected: transaction id and reference are required");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "transactionId and reference are required"));
        }
        Optional<Tenant> tenant = tenantOf(n);
        if (tenant.isEmpty() || tenant.get().usesGepg()) {
            record(null, n.reference(), false, start, "Rejected: unknown reference");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown reference"));
        }
        PaymentProvider provider = provider(n.provider());
        String pspName = provider != null ? provider.label() : n.provider();
        String channel = provider == null ? null : provider.kind() == PaymentProvider.Kind.BANK ? "BANK" : "MOBILE";
        String receipt = (provider != null ? provider.name() : "COL") + "-" + n.transactionId().trim();
        BillingService.PaymentNotification pn = new BillingService.PaymentNotification(n.billId(), n.reference() == null ? null : n.reference().replaceAll("\\s", ""),
                receipt.length() > 40 ? receipt.substring(0, 40) : receipt, n.transactionId().trim(), pspName, channel, n.amount(), n.payerPhone(), n.paidAt());
        try {
            TenantContext.runAs(tenant.get().toInfo(), () -> billing.recordPayment(pn));
        } catch (RuntimeException e) {
            record(tenant.get().getId(), n.reference(), false, start, "Not recorded: " + e.getMessage());
            throw e;
        }
        record(tenant.get().getId(), n.reference(), true, start, "Payment recorded");
        return ResponseEntity.ok(Map.of("status", "ACCEPTED"));
    }

    private Optional<Tenant> tenantOf(Notification n) {
        Long id = PaymentReference.tenantOf(n.reference());
        if (id == null && n.billId() != null && n.billId().contains("-")) {
            try {
                id = Long.parseLong(n.billId().substring(0, n.billId().indexOf('-')));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return id == null ? Optional.empty() : tenants.findById(id);
    }

    private static PaymentProvider provider(String code) {
        if (code == null) return null;
        try {
            return PaymentProvider.valueOf(code.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void record(Long tenantId, String reference, boolean ok, long start, String detail) {
        calls.record(tenantId, IntegrationCallRecorder.ExternalSystem.COLLECTION, "paymentNotification", true, !props.isSimulated(),
                ok ? IntegrationCallRecorder.Outcome.SUCCESS : IntegrationCallRecorder.Outcome.FAILURE,
                (System.nanoTime() - start) / 1_000_000, reference == null ? null : "reference " + reference, detail);
    }
}
