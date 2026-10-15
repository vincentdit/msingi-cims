package tz.co.msingi.cims.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Development: the email is written to the log (and stays in the outbox); nothing leaves the server. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.email", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedEmailGateway implements EmailGateway {

    @Override
    public Result send(Outgoing m) {
        log.info("[Email simulated] to {} — {}{}", mask(m.to()), m.subject(), m.attachmentName() == null ? "" : " [" + m.attachmentName() + "]");
        return new Result(true, "SIM-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}
