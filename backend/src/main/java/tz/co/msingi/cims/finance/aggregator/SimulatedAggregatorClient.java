package tz.co.msingi.cims.finance.aggregator;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rehearsal inside CIMS: no money moves. A prompt "is paid" a few seconds after it is sent, except to a phone ending in 9, where the
 * payer "has too little balance". Lets the whole flow (portal button, waiting, receipt) be tried before an aggregator account exists.
 */
@Component
public class SimulatedAggregatorClient implements AggregatorClient {

    static final long PAY_AFTER_MS = 4000;
    private final Map<String, Sent> sent = new ConcurrentHashMap<>();

    private record Sent(Push push, Instant at, String ref) {
    }

    @Override
    public AggregatorProvider provider() {
        return AggregatorProvider.SIMULATED;
    }

    @Override
    public Result push(Account account, Push p) {
        String ref = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        sent.put(p.orderId(), new Sent(p, Instant.now(), ref));
        return new Result(State.PENDING, ref, null, null, null, p.phone(), "Prompt sent (rehearsal)", null);
    }

    @Override
    public Result status(Account account, String orderId, String providerRef) {
        Sent s = sent.get(orderId);
        if (s == null) return Result.of(State.UNKNOWN, "Not known to the rehearsal");
        if (s.push().phone().endsWith("9")) return new Result(State.FAILED, s.ref(), null, null, null, s.push().phone(), "Insufficient balance (rehearsal)", null);
        if (Instant.now().isBefore(s.at().plusMillis(PAY_AFTER_MS))) return new Result(State.PENDING, s.ref(), null, null, null, s.push().phone(), "Waiting for the PIN (rehearsal)", null);
        return new Result(State.PAID, s.ref(), "SIMTX" + Math.abs(orderId.hashCode()), s.push().amount(), "M-Pesa (rehearsal)", s.push().phone(),
                "Paid (rehearsal)", OffsetDateTime.now());
    }

    /** Rehearsal notifications: {"orderId", "status": PAID|FAILED, "transactionId", "amount"}. */
    @Override
    public Notification read(Account account, Map<String, String> headers, String body) {
        JsonNode b = AggregatorHttp.parse(body);
        String status = AggregatorHttp.text(b, "status");
        State st = "PAID".equalsIgnoreCase(status) ? State.PAID : "FAILED".equalsIgnoreCase(status) ? State.FAILED : State.UNKNOWN;
        return new Notification(AggregatorHttp.text(b, "orderId"), AggregatorHttp.text(b, "reference"),
                new Result(st, null, AggregatorHttp.text(b, "transactionId"), AggregatorHttp.amount(b, "amount"), "M-Pesa (rehearsal)",
                        AggregatorHttp.text(b, "phone"), status, OffsetDateTime.now()));
    }

    @Override
    public void test(Account account) {
        // nothing to reach
    }
}
