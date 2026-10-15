package tz.co.msingi.cims.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cims.email.* — mode=simulated (written to the log and the outbox) or smtp (spring.mail.* settings; in development point it at the
 * Mailpit container to read the mails in a browser). from = the sender address; the institution's name is the sender name.
 */
@ConfigurationProperties(prefix = "cims.email")
public record EmailProperties(String mode, String from, Boolean async) {
    public EmailProperties {
        if (mode == null) mode = "simulated";
        if (from == null) from = "no-reply@cims.msingi.co.tz";
        if (async == null) async = true;
    }

    public boolean isSimulated() {
        return "simulated".equalsIgnoreCase(mode);
    }
}
