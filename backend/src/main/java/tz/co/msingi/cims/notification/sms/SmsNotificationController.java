package tz.co.msingi.cims.notification.sms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.notification.MessageDispatcher;
import tz.co.msingi.cims.notification.SmsGateway;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Delivery reports the SMS provider posts to Msingi's address (shown in the console, with a secret part only Msingi and the
 * provider know). Each report is matched to its institution and message through the provider's message id.
 */
@Slf4j
@Tag(name = "Messaging")
@RestController
@RequiredArgsConstructor
public class SmsNotificationController {

    private final SmsProviderService providers;
    private final MessageDispatcher dispatcher;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;

    @Operation(summary = "Delivery reports posted by the SMS provider (NextSMS or Beem)")
    @PostMapping("/api/integrations/sms/{provider}/{token}")
    public ResponseEntity<Map<String, Object>> report(@PathVariable String provider, @PathVariable String token, @RequestBody(required = false) String body) {
        SmsProvider p;
        try {
            p = SmsProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (!providers.tokenMatches(p, token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "unknown address"));
        }
        List<SmsProviderClient.Report> reports = providers.client(p).parseNotification(body == null ? "" : body);
        int applied = 0, unknown = 0;
        for (SmsProviderClient.Report r : reports) {
            List<Map<String, Object>> rows = jdbc.queryForList("select tenant_id, message_id from sms_provider_refs where provider = ? and provider_ref = ?",
                    p.name(), r.providerRef());
            if (rows.isEmpty()) {
                unknown++;
                continue;
            }
            Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
            Long messageId = ((Number) rows.get(0).get("message_id")).longValue();
            var tenant = tenants.findById(tenantId);
            if (tenant.isEmpty()) continue;
            SmsGateway.DeliveryReport report = new SmsGateway.DeliveryReport(tenantId + "-" + messageId, r.providerRef(), r.status(), r.error());
            if (TenantContext.callAs(tenant.get().toInfo(), () -> dispatcher.applyReportInNewTx(report))) applied++;
        }
        if (unknown > 0) log.info("{} delivery report(s) from {} for unknown messages", unknown, p.label());
        return ResponseEntity.ok(Map.of("status", "ACCEPTED", "applied", applied, "unknown", unknown));
    }
}
