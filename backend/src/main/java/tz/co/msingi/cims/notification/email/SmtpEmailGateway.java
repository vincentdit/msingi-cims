package tz.co.msingi.cims.notification.email;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Sends through the SMTP server in spring.mail.* (an institution's or Msingi's mail service; Mailpit in development). */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.email", name = "mode", havingValue = "smtp")
public class SmtpEmailGateway implements EmailGateway {

    private final JavaMailSender sender;

    @Override
    public Result send(Outgoing m) {
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, m.attachment() != null, StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(m.from(), m.fromName(), StandardCharsets.UTF_8.name()));
            h.setTo(m.toName() == null ? new InternetAddress(m.to()) : new InternetAddress(m.to(), m.toName(), StandardCharsets.UTF_8.name()));
            h.setSubject(m.subject());
            h.setText(m.text(), false);
            if (m.attachment() != null) {
                h.addAttachment(m.attachmentName(), new ByteArrayResource(m.attachment()), m.attachmentType());
            }
            msg.setHeader("X-CIMS-Reference", m.reference());
            sender.send(msg);
            return new Result(true, msg.getMessageID(), null);
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, null, err.length() > 280 ? err.substring(0, 280) : err);
        }
    }
}
