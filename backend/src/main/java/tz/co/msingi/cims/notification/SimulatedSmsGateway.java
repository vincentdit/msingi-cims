package tz.co.msingi.cims.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Development SMS gateway: writes messages to the log instead of sending them and reports delivery at once.
 * Numbers ending in 000 simulate an unreachable handset (delivery fails); numbers ending in 999 are refused by the gateway.
 * Replace with an aggregator adapter for production (cims.sms.mode=live).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.sms", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedSmsGateway implements SmsGateway {

    @Override
    public Result send(String phone, String text, String senderId, String reference) {
        if (phone.endsWith("999")) {
            log.info("[SMS -> {}] refused by simulated gateway", maskPhone(phone));
            return new Result(false, null, "Number rejected by the gateway", null);
        }
        log.info("[SMS -> {}] {}", maskPhone(phone), text);
        String ref = "SIM-" + UUID.randomUUID().toString().substring(0, 13);
        boolean unreachable = phone.endsWith("000");
        return new Result(true, ref, null, new DeliveryReport(reference, ref, unreachable ? "FAILED" : "DELIVERED",
                unreachable ? "Handset unreachable" : null));
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
