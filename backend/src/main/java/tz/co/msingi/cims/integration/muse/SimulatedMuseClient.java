package tz.co.msingi.cims.integration.muse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Development MUSE: posts a balanced journal whose revenue lines all have a GFS code; refuses anything else. */
@Component
@ConditionalOnProperty(prefix = "cims.muse", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedMuseClient implements MuseClient {

    private final Map<String, String> posted = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1000);

    @Override
    public PostResult post(Journal j) {
        if (posted.containsKey(j.batchRef())) return new PostResult("DUPLICATE", posted.get(j.batchRef()), "Already posted");
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Line l : j.lines()) {
            dr = dr.add(l.debit());
            cr = cr.add(l.credit());
            if (l.credit().signum() > 0 && (l.gfsCode() == null || l.gfsCode().isBlank())) {
                return new PostResult("REJECTED", null, "A revenue line has no GFS code: " + l.description());
            }
        }
        if (j.lines().isEmpty()) return new PostResult("REJECTED", null, "The journal has no lines");
        if (dr.compareTo(cr) != 0) return new PostResult("REJECTED", null, "Debits and credits differ");
        String ref = "SIM/JV/" + seq.incrementAndGet();
        posted.put(j.batchRef(), ref);
        return new PostResult("POSTED", ref, "Posted");
    }
}
