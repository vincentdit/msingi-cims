package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.notification.MessagingRepositories.SmsTopupRepository;
import tz.co.msingi.cims.notification.SmsTopup;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Development only: a starting SMS bundle for the demo institution, so bulk messages can be tried. */
@Component
@Order(3)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoMessagingSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final SmsTopupRepository topups;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> {
            if (topups.count() > 0) return;
            SmsTopup u = new SmsTopup();
            u.setUnits(5000);
            u.setAmountPaid(new BigDecimal("125000"));
            u.setReference("DEMO-BUNDLE-1");
            u.setRecordedAt(OffsetDateTime.now());
            u.setRecordedByName("Demo data");
            topups.save(u);
        })));
    }
}
