package tz.co.msingi.cims.finance.collection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Port to the payment collector of a private or faith-based institution (an aggregator such as Selcom, AzamPay or ClickPesa, or a bank's
 * collection service). CIMS issues the payment reference; the collector is told the expected bill so it can accept payments quoting it
 * and report each one to {@code /api/integrations/collections/payment-notification}.
 */
public interface CollectionGateway {

    /** Registers the bill with the collector. Returns false when the collector could not be reached (the reference still works for statements). */
    boolean registerBill(Bill bill);

    record Bill(String reference, String billId, String payerName, String payerPhone, String description, BigDecimal amount,
                String currency, OffsetDateTime expiresAt) {
    }
}
