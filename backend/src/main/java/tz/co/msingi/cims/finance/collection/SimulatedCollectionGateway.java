package tz.co.msingi.cims.finance.collection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development collector: accepts every bill. Payments are simulated from the portals or posted to the callback with an HMAC signature. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.collection", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedCollectionGateway implements CollectionGateway {

    @Override
    public boolean registerBill(Bill bill) {
        log.info("[Collection simulated] bill {} -> reference {} for TZS {}", bill.billId(), bill.reference(), bill.amount());
        return true;
    }
}
