package tz.co.msingi.govmocks.muse;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MUSE's side (FR-INT-07): receives a summarised journal of fee collections. A journal is refused when debits and credits
 * differ, when a revenue (credit) line has no GFS code, or when it has no lines. The same batch reference twice is answered
 * with the journal already posted (409), so a retry never posts twice.
 */
@RestController
public class MuseMockController {

    private static final Logger log = LoggerFactory.getLogger(MuseMockController.class);

    private final MockSwitches switches;
    private final String apiKey;
    private final Map<String, Map<String, Object>> journals = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(5000);

    public MuseMockController(MockSwitches switches, @Value("${mock.muse.api-key}") String apiKey) {
        this.switches = switches;
        this.apiKey = apiKey;
    }

    @PostMapping("/muse/api/v1/journals")
    public ResponseEntity<Map<String, Object>> post(@RequestHeader(value = "X-Api-Key", required = false) String key, @RequestBody JsonNode body) {
        var refused = switches.refuse("MUSE", apiKey, key);
        if (refused != null) return refused;
        String ref = body.path("batch_ref").asText("");
        if (ref.isBlank()) return ResponseEntity.unprocessableEntity().body(Map.of("status", "REJECTED", "message", "batch_ref is required"));
        if (journals.containsKey(ref)) return ResponseEntity.status(HttpStatus.CONFLICT).body(journals.get(ref));
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        int lines = 0;
        String problem = null;
        for (JsonNode l : body.path("lines")) {
            lines++;
            BigDecimal d = new BigDecimal(l.path("debit").asText("0"));
            BigDecimal c = new BigDecimal(l.path("credit").asText("0"));
            dr = dr.add(d);
            cr = cr.add(c);
            if (c.signum() > 0 && l.path("gfs_code").asText("").isBlank()) problem = "Line " + lines + " credits revenue without a GFS code";
        }
        if (lines == 0) problem = "The journal has no lines";
        else if (dr.compareTo(cr) != 0) problem = "Debits (" + dr.toPlainString() + ") and credits (" + cr.toPlainString() + ") differ";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_ref", ref);
        out.put("vote_code", body.path("vote_code").asText(""));
        out.put("status", problem == null ? "POSTED" : "REJECTED");
        out.put("muse_ref", problem == null ? "MUSE/JV/" + seq.incrementAndGet() : null);
        out.put("message", problem == null ? "Posted" : problem);
        out.put("lines", lines);
        out.put("total", dr);
        out.put("received_at", OffsetDateTime.now().toString());
        if (problem == null) journals.put(ref, out);
        log.info("[MUSE mock] journal {}: {} ({} lines, {})", ref, out.get("status"), lines, dr);
        return problem == null ? ResponseEntity.ok(out) : ResponseEntity.unprocessableEntity().body(out);
    }

    @GetMapping("/muse/api/v1/journals/{ref}")
    public ResponseEntity<Map<String, Object>> get(@RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable String ref) {
        var refused = switches.refuse("MUSE", apiKey, key);
        if (refused != null) return refused;
        Map<String, Object> j = journals.get(ref);
        return j == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(j);
    }

    @GetMapping("/mock/muse")
    public Map<String, Object> state() {
        return Map.of("journals", journals.values());
    }

    @PostMapping("/mock/muse/reset")
    public ResponseEntity<Void> reset() {
        journals.clear();
        return ResponseEntity.noContent().build();
    }
}
