package tz.co.msingi.cims.finance.collection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production without a collector integration: nothing is registered anywhere. Payers quote the CIMS reference on mobile money or at
 * the bank, and the Accountant confirms the payments by importing the day's statement on the reconciliation page (or records a bank
 * remittance). A collector that signs its notifications with the shared secret can still post to the callback.
 */
@Component
@ConditionalOnProperty(prefix = "cims.collection", name = "mode", havingValue = "statement")
public class StatementCollectionGateway implements CollectionGateway {

    @Override
    public boolean registerBill(Bill bill) {
        return true;
    }
}
