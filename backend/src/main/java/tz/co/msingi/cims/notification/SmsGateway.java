package tz.co.msingi.cims.notification;

/**
 * The SMS aggregator. {@code reference} is our own "tenantId-messageId", passed to the aggregator and returned in its
 * delivery reports so the report can be matched to the institution and message.
 */
public interface SmsGateway {

    Result send(String phone, String text, String senderId, String reference);

    /**
     * @param accepted        the aggregator took the message
     * @param providerRef     the aggregator's message id
     * @param error           why it was refused
     * @param simulatedReport a delivery report the simulated gateway produces at once (null for live gateways, which post reports later)
     */
    record Result(boolean accepted, String providerRef, String error, DeliveryReport simulatedReport) {
    }

    record DeliveryReport(String reference, String providerRef, String status, String error) {
    }
}
