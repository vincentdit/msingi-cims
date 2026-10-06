package tz.co.msingi.cims.finance.gepg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Development GePG: returns a 12-digit control number starting with "99" immediately.
 * Payments are simulated through {@code POST /api/applicant/payments/simulate} or by posting
 * a signed notification to the callback endpoint.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.gepg", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedGepgGateway implements GepgGateway {

    private final SecureRandom random = new SecureRandom();

    @Override
    public ControlNumberResponse submitBill(BillRequest bill) {
        String controlNo = "99" + String.format("%010d", Math.abs(random.nextLong()) % 10_000_000_000L);
        log.info("[GePG simulated] bill {} -> control number {} for TZS {}", bill.billId(), controlNo, bill.amount());
        return ControlNumberResponse.ok(controlNo);
    }
}
