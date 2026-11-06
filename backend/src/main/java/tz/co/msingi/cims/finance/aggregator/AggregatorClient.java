package tz.co.msingi.cims.finance.aggregator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/** One payment aggregator's API, as CIMS uses it: prompt a phone, ask what became of it, read its notifications. */
public interface AggregatorClient {

    AggregatorProvider provider();

    /** The institution's account with the aggregator (credentials decrypted), and where the aggregator should notify CIMS. */
    record Account(AggregatorProvider provider, boolean live, Map<String, String> fields, String baseUrl, String authUrl, String notifyUrl) {
        public String field(String name) {
            String v = fields.get(name);
            return v == null || v.isBlank() ? null : v.trim();
        }

        @Override
        public String toString() {
            return "Account[" + provider + (live ? " live" : " sandbox") + "]";
        }
    }

    /** A prompt to the payer's phone. phone is 255XXXXXXXXX; network is the mobile money service when the aggregator needs it. */
    record Push(String orderId, String reference, String phone, String network, BigDecimal amount, String description, String payerName,
                String payerEmail) {
    }

    enum State { PENDING, PAID, FAILED, UNKNOWN }

    /** What the aggregator said: its own id for the request, the state, and for a payment the transaction, amount and channel. */
    record Result(State state, String providerRef, String transactionId, BigDecimal amount, String channel, String phone, String message,
                  OffsetDateTime paidAt) {
        public static Result of(State state, String message) {
            return new Result(state, null, null, null, null, null, message, null);
        }
    }

    /** A notification from the aggregator: which request (or which CIMS reference) it is about, and the outcome. */
    record Notification(String orderId, String reference, Result result) {
    }

    Result push(Account account, Push push);

    /** What became of a request; {@link State#UNKNOWN} when the aggregator cannot be asked. */
    Result status(Account account, String orderId, String providerRef);

    /** Reads (and where the aggregator signs them, verifies) a notification; throws {@link SecurityException} when it is not genuine. */
    Notification read(Account account, Map<String, String> headers, String body);

    /** Proves the credentials work (a token, or a harmless query); throws with the aggregator's answer when they do not. */
    void test(Account account);
}
