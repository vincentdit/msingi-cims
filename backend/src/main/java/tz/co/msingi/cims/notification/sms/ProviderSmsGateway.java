package tz.co.msingi.cims.notification.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.notification.SmsGateway;

import java.util.Optional;

/**
 * Sends through the SMS provider Msingi has switched on in the console (cims.sms.mode=live). The provider's message id is kept
 * with the institution and message it belongs to, so the delivery reports the provider posts to the platform address can be
 * applied. With no provider switched on, messages are refused (and retried) rather than lost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.sms", name = "mode", havingValue = "live")
public class ProviderSmsGateway implements SmsGateway {

    private final SmsProviderService providers;
    private final JdbcTemplate jdbc;

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public Result send(String phone, String text, String senderId, String reference) {
        Optional<SmsProviderService.Active> active = providers.active();
        if (active.isEmpty()) return new Result(false, null, "No SMS provider is switched on in the Msingi console", null);
        SmsProviderClient.Account a = active.get().account();
        String sender = senderId == null || senderId.isBlank() ? active.get().defaultSender() : senderId;
        long tenantId = -1, messageId = 0;
        try {
            tenantId = Long.parseLong(reference.substring(0, reference.indexOf('-')));
            messageId = Long.parseLong(reference.substring(reference.indexOf('-') + 1));
        } catch (RuntimeException ignored) {
            // a reference CIMS did not make: sent, but its reports cannot be matched
        }
        SmsProviderClient.Sent s = providers.client(a.provider()).send(a, phone, text, sender, reference, messageId);
        if (!s.accepted()) return new Result(false, null, s.error(), null);
        if (tenantId > 0 && s.providerRef() != null) {
            jdbc.update("insert into sms_provider_refs (provider, provider_ref, tenant_id, message_id) values (?, ?, ?, ?) on conflict do nothing",
                    a.provider().name(), s.providerRef(), tenantId, messageId);
        }
        return new Result(true, s.providerRef(), null, null);
    }

    @Override
    public Optional<DeliveryReport> status(String providerRef, String phone) {
        Optional<SmsProviderService.Active> active = providers.active();
        if (active.isEmpty() || providerRef == null) return Optional.empty();
        SmsProviderClient.Account a = active.get().account();
        try {
            return providers.client(a.provider()).status(a, providerRef, phone)
                    .map(r -> new DeliveryReport(null, providerRef, r.status(), r.error()));
        } catch (RuntimeException e) {
            log.debug("Delivery report for {} not available: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
    }
}
