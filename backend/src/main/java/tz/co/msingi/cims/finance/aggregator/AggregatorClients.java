package tz.co.msingi.cims.finance.aggregator;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The adapter for each aggregator. */
@Component
public class AggregatorClients {

    private final Map<AggregatorProvider, AggregatorClient> byProvider = new EnumMap<>(AggregatorProvider.class);

    public AggregatorClients(List<AggregatorClient> clients) {
        clients.forEach(c -> byProvider.put(c.provider(), c));
    }

    public AggregatorClient of(AggregatorProvider p) {
        AggregatorClient c = byProvider.get(p);
        if (c == null) throw new IllegalStateException("No adapter for " + p);
        return c;
    }
}
