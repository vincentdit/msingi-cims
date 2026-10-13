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
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Receives payment notifications from GePG. The institution is identified from the bill id
 * we generated ("tenantId-invoiceId-n"), so no cross-institution lookup is needed.
 *
 * Simulated mode verifies an HMAC-SHA256 signature (header X-Gepg-Signature) over the raw body.
 * The live adapter must verify the GePG RSA signature per the GePG specification before posting.
 */
@Slf4j
@Tag(name = "Integrations — GePG")
@RestController
@RequestMapping("/api/integrations/gepg")
@RequiredArgsConstructor
public class GepgCallbackController {

    private final BillingService billing;
    private final TenantRepository tenants;
    private final GepgProperties props;
    private final ObjectMapper mapper;
    private final tz.co.msingi.cims.integration.log.IntegrationCallRecorder calls;

    @Operation(summary = "GePG payment notification callback")
    @PostMapping("/payment-notification")
    public ResponseEntity<Map<String, String>> paymentNotification(@RequestBody String body,
                                                                   @RequestHeader(name = "X-Gepg-Signature", required = false) String signature) throws Exception {
        long start = System.nanoTime();
        if (!verify(body, signature)) {
            log.warn("Rejected GePG notification with invalid signature");
            inbound(null, null, false, start, "Rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "invalid signature"));
        }
        BillingService.PaymentNotification n = mapper.readValue(body, BillingService.PaymentNotification.class);
        Optional<Tenant> tenant = tenantFromBillId(n.billId());
        if (tenant.isEmpty()) {
            inbound(null, n.billId(), false, start, "Rejected: unknown bill");
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown bill"));
        }
        try {
            TenantContext.runAs(tenant.get().toInfo(), () -> billing.recordPayment(n));
        } catch (RuntimeException e) {
            inbound(tenant.get().getId(), n.billId(), false, start, "Not recorded: " + e.getMessage());
            throw e;
        }
        inbound(tenant.get().getId(), n.billId(), true, start, "Payment recorded");
        return ResponseEntity.ok(Map.of("status", "ACCEPTED"));
    }

    private void inbound(Long tenantId, String billId, boolean ok, long start, String detail) {
        calls.record(tenantId, tz.co.msingi.cims.integration.log.IntegrationCallRecorder.ExternalSystem.GEPG, "paymentNotification", true,
                !props.isSimulated(), ok ? tz.co.msingi.cims.integration.log.IntegrationCallRecorder.Outcome.SUCCESS
                        : tz.co.msingi.cims.integration.log.IntegrationCallRecorder.Outcome.FAILURE,
                (System.nanoTime() - start) / 1_000_000, billId == null ? null : "bill " + billId, detail);
    }

    private Optional<Tenant> tenantFromBillId(String billId) {
        if (billId == null || !billId.contains("-")) {
            return Optional.empty();
        }
        try {
            return tenants.findById(Long.parseLong(billId.substring(0, billId.indexOf('-'))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private boolean verify(String body, String signature) throws Exception {
        if (!props.isSimulated()) {
            // TODO(live GePG): verify the RSA signature with the GePG public certificate.
            return false;
        }
        if (signature == null) {
            return false;
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
