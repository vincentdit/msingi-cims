package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Application-fee reminders (BP-02): an applicant who asked for a control number and has not paid is reminded by SMS once two
 * days later, and once more when the window closes within three days. Each reminder is sent at most once per application, so the
 * daily run can be repeated safely. A window can switch reminders off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationFeeReminders {

    static final Duration UNPAID_AFTER = Duration.ofDays(2);
    static final Duration CLOSING_WITHIN = Duration.ofDays(3);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final Messaging messaging;
    private final AuditService audit;

    public record RunResult(int unpaid, int closing) {
    }

    private record Due(Long applicationId, String phone, String window, OffsetDateTime closesAt, Long invoiceId, BigDecimal balance,
                       OffsetDateTime billedAt) {
    }

    /** Sends the reminders due now for the current institution. */
    @Transactional
    public RunResult run(OffsetDateTime now) {
        List<Due> due = jdbc.query("""
                select a.id, ap.phone, w.name, w.closes_at, i.id as invoice_id, i.total_amount - i.paid_amount as balance, i.created_at
                from applications a
                join admission_windows w on w.id = a.window_id
                join applicants ap on ap.id = a.applicant_id
                join invoices i on i.id = a.fee_invoice_id
                where w.status = 'PUBLISHED' and w.fee_reminders and w.opens_at <= ? and w.closes_at > ?
                  and a.status in ('DRAFT', 'PAYMENT_PENDING') and not a.fee_paid
                  and i.status in ('ISSUED', 'PARTIALLY_PAID') and ap.phone is not null
                order by a.id""", (rs, n) -> new Due(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                rs.getLong(5), rs.getBigDecimal(6), rs.getObject(7, OffsetDateTime.class)), now, now);
        int unpaid = 0, closing = 0;
        for (Due d : due) {
            boolean closingSoon = !d.closesAt().isAfter(now.plus(CLOSING_WITHIN));
            boolean waitedLongEnough = !d.billedAt().isAfter(now.minus(UNPAID_AFTER));
            if (closingSoon) {
                if (record(d.applicationId(), "CLOSING")) {
                    record(d.applicationId(), "UNPAID");        // one message is enough: no second one for the same reason
                    send(d);
                    closing++;
                }
            } else if (waitedLongEnough && record(d.applicationId(), "UNPAID")) {
                send(d);
                unpaid++;
            }
        }
        if (unpaid + closing > 0) {
            audit.record("APPLICATION_FEE_REMINDERS_SENT", "AdmissionWindow", null, Map.of("unpaid", unpaid, "closing", closing));
            log.info("Application-fee reminders: {} after two days, {} before closing", unpaid, closing);
        }
        return new RunResult(unpaid, closing);
    }

    /** Records that the reminder was sent; false if it already was. */
    private boolean record(Long applicationId, String kind) {
        return jdbc.update("insert into application_fee_reminders (tenant_id, application_id, kind) values (cims_current_tenant(), ?, ?) "
                + "on conflict (application_id, kind) do nothing", applicationId, kind) == 1;
    }

    private void send(Due d) {
        ControlNumber cn = billing.currentControlNumber(d.invoiceId());
        messaging.event("APPLICATION_FEE_REMINDER", d.phone(), Map.of("window", d.window(), "amount", "TZS " + new DecimalFormat("#,##0").format(d.balance()),
                "controlNo", cn == null || cn.getControlNo() == null ? "—" : cn.getControlNo(),
                "deadline", DAY.format(d.closesAt().atZoneSameInstant(ZONE))));
    }
}
