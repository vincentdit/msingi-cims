package tz.co.msingi.govmocks.nida;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NIDA's side (FR-INT-06): checks a National Identification Number against the holder's names and date of birth.
 * A NIN is 20 digits (hyphens allowed) and starts with the date of birth as YYYYMMDD. Rules:
 * <pre>
 *   not 20 digits                          INVALID
 *   last digit 9                           NOT_FOUND
 *   first 8 digits differ from birth date  MISMATCH
 *   otherwise                              VALID (names returned as NIDA holds them, in capitals)
 * </pre>
 * Only the last four digits are logged.
 */
@RestController
public class NidaMockController {

    private static final Logger log = LoggerFactory.getLogger(NidaMockController.class);

    private final MockSwitches switches;
    private final String apiKey;
    private final List<Map<String, String>> checks = new CopyOnWriteArrayList<>();

    public NidaMockController(MockSwitches switches, @Value("${mock.nida.api-key}") String apiKey) {
        this.switches = switches;
        this.apiKey = apiKey;
    }

    @PostMapping("/nida/api/v1/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestHeader(value = "X-Api-Key", required = false) String key, @RequestBody JsonNode body) {
        var refused = switches.refuse("NIDA", apiKey, key);
        if (refused != null) return refused;
        String nin = body.path("nin").asText("").replaceAll("[\\s-]", "");
        String dob = body.path("date_of_birth").asText("");
        String status;
        if (!nin.matches("\\d{20}")) status = "INVALID";
        else if (nin.endsWith("9")) status = "NOT_FOUND";
        else if (!nin.substring(0, 8).equals(dob.replace("-", ""))) status = "MISMATCH";
        else status = "VALID";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        if ("VALID".equals(status)) {
            out.put("first_name", body.path("first_name").asText("").toUpperCase());
            out.put("surname", body.path("surname").asText("").toUpperCase());
            out.put("date_of_birth", dob);
        }
        String last4 = nin.length() >= 4 ? nin.substring(nin.length() - 4) : nin;
        checks.add(0, Map.of("nin", "…" + last4, "status", status, "at", OffsetDateTime.now().toString()));
        log.info("[NIDA mock] NIN …{}: {}", last4, status);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/mock/nida")
    public Map<String, Object> state() {
        return Map.of("checks", checks);
    }
}
