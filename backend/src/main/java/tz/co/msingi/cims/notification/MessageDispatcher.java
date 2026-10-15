package tz.co.msingi.cims.notification;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessagingSettingsRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hands queued messages to the SMS gateway once the transaction that queued them has committed (transactional outbox),
 * retries what the gateway refused (up to three attempts, then FAILED) and applies delivery reports.
 */
@Slf4j
@Component
public class MessageDispatcher {

    private final JobLock jobLock;

    static final int MAX_ATTEMPTS = 3;
    private static final Object KEY = new Object();

    private final MessageRepository messages;
    private final MessagingSettingsRepository settings;
    private final TenantRepository tenants;
    private final SmsGateway gateway;
    private final TransactionTemplate newTx;
    private final boolean async;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sms-dispatch");
        t.setDaemon(true);
        return t;
    });

    public MessageDispatcher(MessageRepository messages, MessagingSettingsRepository settings, TenantRepository tenants, SmsGateway gateway,
                             PlatformTransactionManager txManager, @Value("${cims.sms.async:true}") boolean async, JobLock jobLock) {
        this.jobLock = jobLock;
        this.messages = messages;
        this.settings = settings;
        this.tenants = tenants;
        this.gateway = gateway;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.async = async;
    }

    /** Sends the message once the current transaction commits (immediately if there is none). */
    @SuppressWarnings("unchecked")
    void afterCommit(TenantContext.TenantInfo tenant, Long messageId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(tenant, List.of(messageId));
            return;
        }
        List<Long> pending = (List<Long>) TransactionSynchronizationManager.getResource(KEY);
        if (pending == null) {
            List<Long> ids = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(KEY, ids);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                    if (status == STATUS_COMMITTED) dispatch(tenant, List.copyOf(ids));
                }
            });
            pending = ids;
        }
        pending.add(messageId);
    }

    private void dispatch(TenantContext.TenantInfo tenant, List<Long> ids) {
        Runnable work = () -> TenantContext.runAs(tenant, () -> ids.forEach(this::deliverSafely));
        if (async) executor.submit(work);
        else work.run();
    }

    private void deliverSafely(Long id) {
        try {
            newTx.executeWithoutResult(s -> deliver(id));
        } catch (RuntimeException e) {
            log.error("Could not dispatch message {}", id, e);
        }
    }

    /** Hands one queued message to the gateway (in the current tenant and transaction). */
    void deliver(Long id) {
        Message m = messages.findById(id).orElse(null);
        if (m == null || m.getStatus() != Message.Status.QUEUED) return;
        m.setAttempts((short) (m.getAttempts() + 1));
        apply(m, send(m, m.getBody()));
    }

    /** For verification codes: sent at once with the real text, which is not stored. */
    void sendNow(Message m, String realBody) {
        m.setAttempts((short) 1);
        SmsGateway.Result r = send(m, realBody);
        apply(m, r);
        if (m.getStatus() == Message.Status.QUEUED) { // the code cannot be resent later: it is not stored
            m.setStatus(Message.Status.FAILED);
        }
    }

    private SmsGateway.Result send(Message m, String body) {
        String sender = settings.findFirstBy().map(MessagingSettings::getSenderId).orElse(null);
        try {
            return gateway.send(m.getPhone(), body, sender, m.getTenantId() + "-" + m.getId());
        } catch (RuntimeException e) {
            log.warn("SMS gateway error for message {}: {}", m.getId(), e.getMessage());
            return new SmsGateway.Result(false, null, "Gateway error: " + e.getMessage(), null);
        }
    }

    private void apply(Message m, SmsGateway.Result r) {
        if (r.accepted()) {
            m.setStatus(Message.Status.SENT);
            m.setChargedParts(m.getChargedParts() + m.getParts());
            m.setProviderRef(r.providerRef());
            m.setSentAt(OffsetDateTime.now());
            m.setError(null);
            if (r.simulatedReport() != null) applyReport(m, r.simulatedReport());
        } else {
            m.setError(r.error() == null ? null : r.error().length() > 300 ? r.error().substring(0, 300) : r.error());
            if (m.getAttempts() >= MAX_ATTEMPTS) m.setStatus(Message.Status.FAILED);
        }
    }

    /** A delivery report from the aggregator (current tenant), in its own transaction. */
    boolean applyReportInNewTx(SmsGateway.DeliveryReport report) {
        return Boolean.TRUE.equals(newTx.execute(s -> applyReport(report)));
    }

    /** A delivery report from the aggregator (current tenant). Returns false when the message is unknown. */
    boolean applyReport(SmsGateway.DeliveryReport report) {
        Message m = null;
        if (report.reference() != null && report.reference().contains("-")) {
            try {
                m = messages.findById(Long.parseLong(report.reference().substring(report.reference().indexOf('-') + 1))).orElse(null);
            } catch (NumberFormatException ignored) {
                // fall back to the aggregator's id
            }
        }
        if (m == null && report.providerRef() != null) m = messages.findByProviderRef(report.providerRef()).orElse(null);
        if (m == null) return false;
        applyReport(m, report);
        return true;
    }

    private void applyReport(Message m, SmsGateway.DeliveryReport report) {
        if (m.getStatus() == Message.Status.QUEUED) return; // a report before the send was recorded: ignore
        switch (report.status() == null ? "" : report.status().toUpperCase()) {
            case "DELIVERED", "DELIVRD" -> {
                m.setStatus(Message.Status.DELIVERED);
                m.setDeliveredAt(OffsetDateTime.now());
                m.setError(null);
            }
            case "FAILED", "UNDELIV", "UNDELIVERED", "EXPIRED", "REJECTED" -> {
                m.setStatus(Message.Status.FAILED);
                m.setError(report.error() == null ? "Not delivered" : report.error());
            }
            default -> {
                // intermediate states (e.g. ACCEPTED, BUFFERED): keep SENT
            }
        }
    }

    /** Retries messages still queued (gateway refused or the application stopped before sending). */
    @Scheduled(cron = "${cims.sms.retry-cron:30 * * * * *}")
    public void retryQueued() {
        jobLock.runOnce("sms-retry", Duration.ofSeconds(50), Duration.ofSeconds(20), this::retryQueuedNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void retryQueuedNow() {
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> retryQueuedHere(java.time.Duration.ofSeconds(20)));
            } catch (RuntimeException e) {
                log.error("SMS retry failed for institution {}", t.getCode(), e);
            }
        }
    }

    /** Retries this institution's messages that have been queued for at least {@code minAge}. */
    public void retryQueuedHere(java.time.Duration minAge) {
        List<Long> ids = newTx.execute(s -> messages.findTop200ByStatusAndQueuedAtBeforeOrderByIdAsc(Message.Status.QUEUED,
                OffsetDateTime.now().minus(minAge).plusNanos(1000)).stream().map(Message::getId).toList());
        if (ids != null) ids.forEach(this::deliverSafely);
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
