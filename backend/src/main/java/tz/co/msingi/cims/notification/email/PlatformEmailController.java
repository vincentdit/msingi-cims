package tz.co.msingi.cims.notification.email;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.console.audit.PlatformAuditService;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The Msingi console's view of email: how the server sends it (simulated or through the mail server set in deploy/.env) and a
 * test message to prove it. The mail server's password stays in the server's settings and is never shown.
 */
@Tag(name = "Platform: email")
@RestController
@RequestMapping("/api/platform/email")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformEmailController {

    public record Status(String mode, String from, String host, Integer port, boolean auth, boolean starttls, boolean ssl, String username) {
    }

    public record TestEmail(@NotBlank @Email String to) {
    }

    public record TestResult(boolean ok, String note, OffsetDateTime at) {
    }

    private final EmailGateway gateway;
    private final EmailProperties props;
    private final PlatformAuditService audit;

    @Value("${spring.mail.host:}")
    private String host;
    @Value("${spring.mail.port:0}")
    private Integer port;
    @Value("${spring.mail.username:}")
    private String username;
    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean auth;
    @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
    private boolean starttls;
    @Value("${spring.mail.properties.mail.smtp.ssl.enable:false}")
    private boolean ssl;

    @GetMapping
    public Status status() {
        boolean smtp = "smtp".equalsIgnoreCase(props.mode());
        return new Status(smtp ? "smtp" : "simulated", props.from(), smtp ? host : null, smtp ? port : null, auth, starttls, ssl,
                smtp && username != null && !username.isBlank() ? username : null);
    }

    @Operation(summary = "Send a test email from CIMS's address (in simulated mode it is only written to the log)")
    @PostMapping("/test")
    public TestResult test(@Valid @RequestBody TestEmail req) {
        EmailGateway.Result r;
        try {
            r = gateway.send(new EmailGateway.Outgoing(props.from(), "Msingi Technologies", req.to().trim(), null, "CIMS test email",
                    "This is a test email from CIMS (Msingi Technologies). If you received it, email sending works.\n\n"
                            + "Institutions' emails are sent from " + props.from() + " under the institution's name, with replies going to the "
                            + "institution's contact email.", null, null, null, "platform-test-" + System.currentTimeMillis(), null));
        } catch (RuntimeException e) {
            r = new EmailGateway.Result(false, null, e.getMessage());
        }
        audit.record("EMAIL_TEST_SENT", null, "PlatformEmail", 1L, Map.of("ok", r.accepted()));
        boolean simulated = !"smtp".equalsIgnoreCase(props.mode());
        String note = r.accepted()
                ? (simulated ? "Written to the server log only: the server is in simulated mode (CIMS_EMAIL_MODE=simulated)."
                : "The mail server accepted the test email to " + req.to().trim() + ". Check the inbox (and the spam folder).")
                : "The mail server refused: " + r.error();
        return new TestResult(r.accepted(), note, OffsetDateTime.now());
    }
}
