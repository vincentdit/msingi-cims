package tz.co.msingi.cims.notification.email;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.notification.email.EmailVerificationService.CodeRequest;
import tz.co.msingi.cims.notification.email.EmailVerificationService.EmailRequest;
import tz.co.msingi.cims.notification.email.EmailVerificationService.EmailStatus;

import java.util.List;
import java.util.Map;

/** The signed-in person's verified email, and the institution's email outbox and switch. */
@Tag(name = "Email")
@RestController
@RequiredArgsConstructor
public class EmailController {

    private final EmailVerificationService verification;
    private final EmailService email;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final tz.co.msingi.cims.platform.audit.AuditService audit;

    public record SwitchRequest(boolean enabled) {
    }

    @GetMapping("/api/account/email")
    @PreAuthorize("isAuthenticated()")
    public EmailStatus status() {
        return verification.status();
    }

    @PostMapping("/api/account/email")
    @PreAuthorize("isAuthenticated()")
    public EmailStatus request(@Valid @RequestBody EmailRequest req) {
        return verification.request(req.email());
    }

    @PostMapping("/api/account/email/verify")
    @PreAuthorize("isAuthenticated()")
    public EmailStatus verify(@Valid @RequestBody CodeRequest req) {
        return verification.verify(req.code());
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/api/messaging/emails")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','ADMISSIONS_OFFICER','BURSAR','DEPUTY_PRINCIPAL_ACADEMIC','AUDITOR','MANAGEMENT_VIEWER')")
    public Map<String, Object> outbox(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        List<EmailService.OutboxRow> rows = email.outbox(page, size);
        return Map.of("enabled", email.enabled(), "rows", rows);
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/api/messaging/emails/switch")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public Map<String, Object> setEnabled(@RequestBody SwitchRequest req) {
        int n = jdbc.update("update messaging_settings set email_enabled = ?", req.enabled());
        if (n == 0) jdbc.update("insert into messaging_settings (tenant_id, email_enabled) values (cims_current_tenant(), ?)", req.enabled());
        audit.record("EMAIL_SWITCHED", "MessagingSettings", null, Map.of("enabled", req.enabled()));
        return Map.of("enabled", email.enabled());
    }
}
