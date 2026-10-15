package tz.co.msingi.cims.finance.gepg;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Port to the Government electronic Payment Gateway (FR-INT-03).
 * The live adapter sends signed bill submissions per the GePG specification and receives the control number asynchronously
 * (a signed callback); the simulated adapter returns one immediately.
 */
public interface GepgGateway {

    ControlNumberResponse submitBill(BillRequest bill);

    /**
     * Asks GePG for its transactions of a day; GePG answers later by callback. Returns false when this adapter cannot do that
     * (the simulator), in which case the institution imports GePG's reconciliation file instead.
     */
    default boolean requestReconciliation(String requestId, LocalDate date) {
        return false;
    }

    /** One line of a bill: what it is for, its amount and the revenue (GFS) code it is collected under. */
    record BillItem(String ref, BigDecimal amount, String gfsCode) {
    }

    record BillRequest(String billId, String payerName, String payerPhone, String payerEmail,
                       String description, BigDecimal amount, String currency, OffsetDateTime expiresAt,
                       String payerId, List<BillItem> items) {

        public BillRequest(String billId, String payerName, String payerPhone, String payerEmail, String description, BigDecimal amount,
                           String currency, OffsetDateTime expiresAt) {
            this(billId, payerName, payerPhone, payerEmail, description, amount, currency, expiresAt, null, List.of());
        }
    }

    record ControlNumberResponse(boolean issued, String controlNo, String errorMessage) {
        public static ControlNumberResponse ok(String controlNo) {
            return new ControlNumberResponse(true, controlNo, null);
        }

        public static ControlNumberResponse pending() {
            return new ControlNumberResponse(false, null, null);
        }

        public static ControlNumberResponse error(String message) {
            return new ControlNumberResponse(false, null, message);
        }
    }
}
