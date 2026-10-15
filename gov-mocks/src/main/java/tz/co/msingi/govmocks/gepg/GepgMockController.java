package tz.co.msingi.govmocks.gepg;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GePG's service provider endpoints (signed XML) and the developer's actions. Paths follow the GePG specification's
 * {@code /api/bill/sigqrequest} and {@code /api/reconciliations/sig_sp_qrequest}.
 */
@RestController
public class GepgMockController {

    private final GepgMockService gepg;
    private final MockSwitches switches;

    public GepgMockController(GepgMockService gepg, MockSwitches switches) {
        this.gepg = gepg;
        this.switches = switches;
    }

    @PostMapping(value = "/api/bill/sigqrequest", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bill(@RequestBody(required = false) byte[] raw) {
        String xml = raw == null ? null : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        if (switches.down("GEPG")) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        return ResponseEntity.ok(safe(() -> gepg.submitBill(xml), "gepgBillSubReqAck"));
    }

    @PostMapping(value = "/api/reconciliations/sig_sp_qrequest", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> reconciliation(@RequestBody(required = false) byte[] raw) {
        String xml = raw == null ? null : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        if (switches.down("GEPG")) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        return ResponseEntity.ok(safe(() -> gepg.requestReconciliation(xml), "gepgSpReconcReqAck"));
    }

    // --- developer -----------------------------------------------------------------------------------------------------

    @GetMapping("/mock/gepg")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rejectBills", gepg.rejectBills());
        m.put("strayPayment", gepg.strayPayment());
        m.put("bills", gepg.bills());
        m.put("payments", gepg.payments());
        m.put("reconciliations", gepg.reconciliations());
        return m;
    }

    @PostMapping("/mock/gepg/bills/{billId}/pay")
    public ResponseEntity<?> pay(@PathVariable String billId, @RequestParam(required = false) BigDecimal amount,
                                 @RequestParam(required = false) String channel) {
        try {
            return ResponseEntity.ok(gepg.pay(billId, amount, channel));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mock/gepg/payments/{payRefId}/resend")
    public ResponseEntity<?> resend(@PathVariable String payRefId) {
        try {
            return ResponseEntity.ok(gepg.resend(payRefId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mock/gepg/reject-bills")
    public Map<String, Object> rejectBills(@RequestParam boolean on) {
        gepg.setRejectBills(on);
        return Map.of("rejectBills", on);
    }

    @PostMapping("/mock/gepg/stray-payment")
    public Map<String, Object> stray(@RequestParam boolean on) {
        gepg.setStrayPayment(on);
        return Map.of("strayPayment", on);
    }

    @PostMapping("/mock/gepg/reset")
    public ResponseEntity<Void> reset() {
        gepg.reset();
        return ResponseEntity.noContent().build();
    }

    private String safe(java.util.function.Supplier<String> call, String ackTag) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            return gepg.ack(ackTag, 7242);
        } catch (RuntimeException e) {
            return gepg.ack(ackTag, 7201);
        }
    }
}
