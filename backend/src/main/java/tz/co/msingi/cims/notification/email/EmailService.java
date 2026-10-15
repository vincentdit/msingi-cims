package tz.co.msingi.cims.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The email channel (BR-33, FR-COM-01, FR-INT-04). Emails are written to the outbox in the caller's transaction and handed to the mail
 * server after it commits (nothing is sent for work that rolls back); failures are retried a few times. Event emails go only to
 * verified addresses. The institution can switch email off.
 */
@Slf4j
@Service
public class EmailService {

    public static final int MAX_ATTEMPTS = 5;
    private static final int MAX_ATTACHMENT = 10 * 1024 * 1024;

    private final EmailRepository emails;
    private final EmailGateway gateway;
    private final EmailProperties props;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "email-dispatch");
        t.setDaemon(true);
        return t;
    });

    public EmailService(EmailRepository emails, EmailGateway gateway, EmailProperties props, TenantRepository tenants, JdbcTemplate jdbc,
                        PlatformTransactionManager txManager) {
        this.emails = emails;
        this.gateway = gateway;
        this.props = props;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Attachment(String name, String contentType, byte[] content) {
    }

    /** Whether the institution sends email at all (Communication › Messages). */
    public boolean enabled() {
        List<Boolean> v = jdbc.queryForList("select email_enabled from messaging_settings", Boolean.class);
        return v.isEmpty() || Boolean.TRUE.equals(v.get(0));
    }

    /** The verified email of the account with this phone number (applicants and students), if any. */
    public Optional<String> verifiedEmailForPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        return jdbc.queryForList("select email from user_accounts where phone = ? and email is not null and email_verified_at is not null and status = 'ACTIVE'",
                String.class, phone).stream().findFirst();
    }

    /** Queues an email; it is sent after the current transaction commits. Returns the outbox id, or null when email is off. */
    public Long queue(String to, String name, EmailMessage.Kind kind, String templateCode, String subject, String body, Attachment attachment) {
        if (to == null || to.isBlank() || (kind != EmailMessage.Kind.SECURITY && !enabled())) return null;
        EmailMessage m = new EmailMessage();
        m.setToAddress(to.trim());
        m.setRecipientName(name);
        m.setKind(kind);
        m.setTemplateCode(templateCode);
        m.setSubject(subject.length() > 200 ? subject.substring(0, 200) : subject);
        m.setBody(body);
        if (attachment != null) {
            if (attachment.content().length > MAX_ATTACHMENT) throw new IllegalArgumentException("Attachment too large for email");
            m.setAttachmentName(attachment.name());
            m.setAttachmentType(attachment.contentType());
            m.setAttachment(attachment.content());
            m.setAttachmentSize(attachment.content().length);
        }
        m.setQueuedAt(OffsetDateTime.now());
        emails.saveAndFlush(m);
        afterCommit(TenantContext.require(), m.getId(), null);
        return m.getId();
    }

    /** A verification code: the outbox keeps the text with the code masked; the real text is sent. */
    public Long queueSecret(String to, String name, String subject, String realBody, String maskedBody) {
        EmailMessage m = new EmailMessage();
        m.setToAddress(to.trim());
        m.setRecipientName(name);
        m.setKind(EmailMessage.Kind.SECURITY);
        m.setTemplateCode("EMAIL_CODE");
        m.setSubject(subject);
        m.setBody(maskedBody);
        m.setQueuedAt(OffsetDateTime.now());
        emails.saveAndFlush(m);
        afterCommit(TenantContext.require(), m.getId(), realBody);
        return m.getId();
    }

    private void afterCommit(TenantContext.TenantInfo tenant, Long id, String realBody) {
        Runnable work = () -> TenantContext.runAs(tenant, () -> deliverSafely(id, realBody));
        Runnable dispatch = () -> {
            if (props.async()) executor.submit(work);
            else work.run();
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch.run();
            }
        });
    }

    private void deliverSafely(Long id, String realBody) {
        try {
            newTx.executeWithoutResult(s -> deliver(id, realBody));
        } catch (RuntimeException e) {
            log.error("Could not send email {}", id, e);
        }
    }

    void deliver(Long id, String realBody) {
        EmailMessage m = emails.findById(id).orElse(null);
        if (m == null || m.getStatus() != EmailMessage.Status.QUEUED) return;
        m.setAttempts((short) (m.getAttempts() + 1));
        String institution = tenants.findById(m.getTenantId()).map(Tenant::getName).orElse("CIMS");
        EmailGateway.Result r;
        try {
            r = gateway.send(new EmailGateway.Outgoing(props.from(), institution, m.getToAddress(), m.getRecipientName(), m.getSubject(),
                    realBody != null ? realBody : m.getBody(), m.getAttachmentName(), m.getAttachmentType(), m.getAttachment(),
                    m.getTenantId() + "-" + m.getId()));
        } catch (RuntimeException e) {
            r = new EmailGateway.Result(false, null, "Mail server error: " + e.getMessage());
        }
        if (r.accepted()) {
            m.setStatus(EmailMessage.Status.SENT);
            m.setProviderRef(r.providerRef());
            m.setSentAt(OffsetDateTime.now());
            m.setError(null);
            m.setAttachment(null);   // not kept once delivered
        } else {
            m.setError(r.error());
            if (realBody != null || m.getAttempts() >= MAX_ATTEMPTS) {
                m.setStatus(EmailMessage.Status.FAILED);   // a verification code cannot be resent later: it is not stored
                m.setAttachment(null);
            }
        }
    }

    /** Retries emails still queued (mail server down at the time). */
    @Scheduled(cron = "${cims.email.retry-cron:0 */5 * * * *}")
    public void retryQueued() {
        for (Tenant t : tenants.findAll()) {
            try {
                TenantContext.runAs(t.toInfo(), () -> {
                    List<Long> ids = newTx.execute(s -> emails.findByStatusAndQueuedAtBeforeAndAttemptsLessThan(EmailMessage.Status.QUEUED,
                            OffsetDateTime.now().minus(Duration.ofMinutes(2)), (short) MAX_ATTEMPTS).stream().map(EmailMessage::getId).toList());
                    if (ids != null) ids.forEach(id -> deliverSafely(id, null));
                });
            } catch (RuntimeException e) {
                log.error("Email retry failed for institution {}", t.getCode(), e);
            }
        }
    }

    public record OutboxRow(Long id, String to, String recipientName, String kind, String templateCode, String subject, String attachmentName,
                            Integer attachmentSize, String status, int attempts, String error, OffsetDateTime queuedAt, OffsetDateTime sentAt) {
    }

    public List<OutboxRow> outbox(int page, int size) {
        return emails.findAllByOrderByQueuedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200))).stream()
                .map(m -> new OutboxRow(m.getId(), mask(m.getToAddress()), m.getRecipientName(), m.getKind().name(), m.getTemplateCode(), m.getSubject(),
                        m.getAttachmentName(), m.getAttachmentSize(), m.getStatus().name(), m.getAttempts(), m.getError(), m.getQueuedAt(), m.getSentAt()))
                .toList();
    }

    static String mask(String email) {
        return SimulatedEmailGateway.mask(email);
    }
}
