package tz.co.msingi.cims.notification.email;

/** Port to the mail server (SMTP) or a simulated one. */
public interface EmailGateway {

    Result send(Outgoing mail);

    /** {@code replyTo}: where answers go (the institution's own contact email), since mail is sent from CIMS's address. */
    record Outgoing(String from, String fromName, String to, String toName, String subject, String text, String attachmentName,
                    String attachmentType, byte[] attachment, String reference, String replyTo) {
    }

    record Result(boolean accepted, String providerRef, String error) {
    }
}
