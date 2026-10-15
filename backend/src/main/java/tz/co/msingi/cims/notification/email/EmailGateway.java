package tz.co.msingi.cims.notification.email;

/** Port to the mail server (SMTP) or a simulated one. */
public interface EmailGateway {

    Result send(Outgoing mail);

    record Outgoing(String from, String fromName, String to, String toName, String subject, String text, String attachmentName,
                    String attachmentType, byte[] attachment, String reference) {
    }

    record Result(boolean accepted, String providerRef, String error) {
    }
}
