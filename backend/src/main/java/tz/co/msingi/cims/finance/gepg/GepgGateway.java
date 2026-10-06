package tz.co.msingi.cims.finance.gepg;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Port to the Government electronic Payment Gateway.
 * The live adapter sends signed bill submissions per the GePG specification and receives the
 * control number asynchronously; the simulated adapter returns one immediately.
 */
public interface GepgGateway {

    ControlNumberResponse submitBill(BillRequest bill);

    record BillRequest(String billId, String payerName, String payerPhone, String payerEmail,
                       String description, BigDecimal amount, String currency, OffsetDateTime expiresAt) {
    }

    record ControlNumberResponse(boolean issued, String controlNo, String errorMessage) {
        public static ControlNumberResponse ok(String controlNo) {
            return new ControlNumberResponse(true, controlNo, null);
        }

        public static ControlNumberResponse pending() {
            return new ControlNumberResponse(false, null, null);
        }
    }
}
