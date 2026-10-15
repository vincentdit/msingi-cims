package tz.co.msingi.cims.integration.tamisemi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Development TAMISEMI: answers with the selections added through {@link #add} (by tests or the demo data); none otherwise.
 * Run gov-mocks with {@code CIMS_TAMISEMI_MODE=live} to add selections from a dashboard.
 */
@Component
@ConditionalOnProperty(prefix = "cims.tamisemi", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedTamisemiClient implements TamisemiClient {

    private final Map<String, List<Selection>> selections = new ConcurrentHashMap<>();

    @Override
    public List<Selection> selections(String institutionCode, int year) {
        return List.copyOf(selections.getOrDefault(institutionCode.toUpperCase() + "|" + year, List.of()));
    }

    public void add(String institutionCode, int year, Selection s) {
        selections.computeIfAbsent(institutionCode.toUpperCase() + "|" + year, k -> new CopyOnWriteArrayList<>()).add(s);
    }
}
