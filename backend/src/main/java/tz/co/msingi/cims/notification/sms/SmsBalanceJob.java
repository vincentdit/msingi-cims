package tz.co.msingi.cims.notification.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.notification.MessagingAdminService;
import tz.co.msingi.cims.notification.email.EmailGateway;
import tz.co.msingi.cims.notification.email.EmailProperties;
import tz.co.msingi.cims.platform.jobs.JobLock;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every three hours: reads the balance of Msingi's account with the SMS provider and adds up the SMS the institutions still hold
 * in their bundles. Every institution's messages draw on that one account, so when it falls below the alert level, or below what
 * the institutions have already paid for, Msingi's staff are emailed (at most once a day) to top it up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsBalanceJob {

    private final JobLock jobLock;
    private final JdbcTemplate jdbc;
    private final SmsProviderService providers;
    private final TenantRepository tenants;
    private final MessagingAdminService messaging;
    private final EmailGateway gateway;
    private final EmailProperties emailProps;

    @Value("${cims.sms.low-balance-alert:5000}")
    private long alertBelow;

    @Scheduled(cron = "${cims.sms.balance-cron:0 7 */3 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("sms-balance", Duration.ofMinutes(10), Duration.ofMinutes(1), this::runNow);
    }

    /** Returns the provider's balance (null when it could not be read or SMS are not live). */
    public Long runNow() {
        if (!providers.live()) return null;
        var active = providers.active().orElse(null);
        if (active == null) return null;
        Long balance;
        try {
            balance = parse(providers.client(active.account().provider()).balance(active.account()));
        } catch (RuntimeException e) {
            log.warn("Could not read the SMS provider's balance: {}", e.getMessage());
            return null;
        }
        long outstanding = outstanding();
        jdbc.update("update platform_sms_settings set last_balance = ?, last_balance_at = now(), outstanding_units = ? where id = 1", balance, outstanding);
        if (balance == null) return null;
        boolean low = balance < alertBelow || balance < outstanding;
        if (!low) {
            jdbc.update("update platform_sms_settings set low_alert_at = null where id = 1");
            return balance;
        }
        int due = jdbc.update("update platform_sms_settings set low_alert_at = now() where id = 1 and (low_alert_at is null or low_alert_at < now() - interval '23 hours')");
        if (due > 0) alert(active.account().provider().label(), balance, outstanding);
        return balance;
    }

    /** The SMS the institutions have bought and not used yet (what Msingi owes them). */
    long outstanding() {
        AtomicLong sum = new AtomicLong();
        for (Tenant t : tenants.findAll()) {
            if (!"ACTIVE".equals(t.getStatus())) continue;
            try {
                TenantContext.runAs(t.toInfo(), () -> sum.addAndGet(Math.max(0, messaging.balance())));
            } catch (RuntimeException e) {
                log.warn("SMS bundle of {} not read: {}", t.getCode(), e.getMessage());
            }
        }
        return sum.get();
    }

    private void alert(String provider, long balance, long outstanding) {
        String subject = String.format(Locale.ENGLISH, "Top up the %s account: %,d SMS left", provider, balance);
        String body = String.format(Locale.ENGLISH, """
                Msingi's %s account has %,d SMS left.
                The institutions still hold %,d SMS in the bundles they have paid for, and the alert level is %,d.

                Every institution's messages are sent through this one account: top it up now so that verification codes, offers and \
                fee reminders keep going out. The balance is checked again every three hours.

                — CIMS""", provider, balance, outstanding, alertBelow);
        List<Map<String, Object>> staff = jdbc.queryForList("select email, full_name from platform_users where status = 'ACTIVE' and email is not null");
        for (Map<String, Object> s : staff) {
            try {
                gateway.send(new EmailGateway.Outgoing(emailProps.from(), "CIMS", (String) s.get("email"), (String) s.get("full_name"), subject, body,
                        null, null, null, "sms-balance-" + System.currentTimeMillis(), null));
            } catch (RuntimeException e) {
                log.warn("SMS balance alert to {} not sent: {}", s.get("email"), e.getMessage());
            }
        }
        log.warn("SMS provider balance low: {} SMS left, {} held by institutions", balance, outstanding);
    }

    static Long parse(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9.]", "");
        if (d.isEmpty()) return null;
        return (long) Math.floor(Double.parseDouble(d));
    }
}
