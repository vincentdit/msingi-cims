package tz.co.msingi.govmocks.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Per-system outage switches (every call answers 503) and the API key check shared by the JSON stand-ins. */
@Component
public class MockSwitches {

    public static final Set<String> SYSTEMS = Set.of("GEPG", "HESLB", "NIDA", "MUSE", "TAMISEMI");

    private final Map<String, Boolean> outage = new ConcurrentHashMap<>();

    public boolean down(String system) {
        return outage.getOrDefault(system, false);
    }

    public void set(String system, boolean on) {
        if (!SYSTEMS.contains(system)) throw new IllegalArgumentException("Unknown system " + system);
        outage.put(system, on);
    }

    public Map<String, Boolean> all() {
        Map<String, Boolean> m = new TreeMap<>();
        SYSTEMS.forEach(s -> m.put(s, down(s)));
        return m;
    }

    /** null when the call may go ahead; otherwise the answer to give (outage or wrong key). */
    public ResponseEntity<Map<String, Object>> refuse(String system, String expectedKey, String givenKey) {
        if (down(system)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", system + " is temporarily unavailable (simulated outage)"));
        }
        if (givenKey == null || !MessageDigest.isEqual(givenKey.getBytes(StandardCharsets.UTF_8), expectedKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or missing X-Api-Key"));
        }
        return null;
    }
}
