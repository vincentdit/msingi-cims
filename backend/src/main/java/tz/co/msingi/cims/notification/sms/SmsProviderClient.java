package tz.co.msingi.cims.notification.sms;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One SMS provider's API: send a message, ask what became of it, read the account balance, read a delivery report it posts. */
public interface SmsProviderClient {

    /**
     * The account Msingi holds with the provider. {@code live} false is the provider's test mode where it has one (NextSMS's test
     * endpoint accepts and answers like the real one but sends nothing).
     */
    record Account(SmsProvider provider, boolean live, Map<String, String> settings, Map<String, String> secrets, String baseUrl,
                   String reportUrl) {

        public String setting(String k) {
            return settings.get(k);
        }

        public String secret(String k) {
            return secrets.get(k);
        }
    }

    /** What the provider said to a message: accepted (with its id) or refused (with why). */
    record Sent(boolean accepted, String providerRef, String error) {
    }

    /**
     * What became of a message. {@code status} is DELIVERED, FAILED or PENDING (still on its way); {@code providerRef} is the
     * provider's id, {@code phone} the number when the provider gives it.
     */
    record Report(String providerRef, String phone, String status, String error) {
    }

    SmsProvider provider();

    /** {@code messageId} is CIMS's own number for the message (some providers carry it back in their reports). */
    Sent send(Account a, String phone, String text, String sender, String reference, long messageId);

    /** What became of a message, when the provider can say; empty while it does not know yet. */
    Optional<Report> status(Account a, String providerRef, String phone);

    /** The SMS credit left on the account, as the provider states it (units or money). */
    String balance(Account a);

    /** The delivery reports in a notification the provider posted (one or several). */
    List<Report> parseNotification(String body);
}
