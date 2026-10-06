package tz.co.msingi.cims.notification;

/** Outbound SMS gateway. Real aggregator adapters implement this interface. */
public interface SmsSender {
    void send(String phone, String message);
}
