package tz.co.msingi.govmocks.nhif;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NHIF's side for students' health insurance, in memory, with the same rules as CIMS's built-in simulator:
 * <pre>
 * Cards, by the last digit: …0 not found · …9 inactive (cover ended on 30 June) · …8 active but held by NEEMA JOHN KIMARO ·
 * other digits active until the next 30 June. Cards issued here or added on the dashboard are known by their holder's name.
 * Registration under the student package: no date of birth → rejected on receipt; registration number ending in 7 → REJECTED
 * (already a member under another scheme); otherwise REGISTERED with a new card valid to 30 June at the end of the academic year.
 * Decisions are released after {@code mock.nhif.processing-seconds} (PENDING until then).
 * </pre>
 */
@RestController
public class NhifMockController {

    private static final Logger log = LoggerFactory.getLogger(NhifMockController.class);
    private static final String BASE = "/nhif/api/v1";

    public static final class Card {
        public String cardNo, status, fullName, scheme, validTo, source;
    }

    public static final class Item {
        public String registrationNo, name, status, cardNo, validTo, reason;
    }

    public static final class Batch {
        public String batchRef, institutionCode, academicYear, receivedAt;
        public long readyAtEpoch;
        public int received;
        public List<Map<String, String>> rejected = new ArrayList<>();
        public List<Item> items = new ArrayList<>();
    }

    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final List<Batch> batches = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> checks = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    private final MockSwitches switches;
    private final String apiKey;
    private final long processingSeconds;

    public NhifMockController(MockSwitches switches, @Value("${mock.nhif.api-key}") String apiKey,
                              @Value("${mock.nhif.processing-seconds:5}") long processingSeconds) {
        this.switches = switches;
        this.apiKey = apiKey;
        this.processingSeconds = processingSeconds;
    }

    @PostMapping(BASE + "/cards/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestHeader(value = "X-Api-Key", required = false) String key, @RequestBody JsonNode body) {
        var refused = switches.refuse("NHIF", apiKey, key);
        if (refused != null) return refused;
        String no = text(body, "card_no");
        if (no == null) return ResponseEntity.unprocessableEntity().body(Map.of("error", "card_no is required"));
        String c = no.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("card_no", c);
        Card known = cards.get(c);
        LocalDate today = LocalDate.now();
        LocalDate nextJune = LocalDate.of(today.getMonthValue() > 6 ? today.getYear() + 1 : today.getYear(), 6, 30);
        if (known != null) {
            out.put("status", known.status);
            out.put("full_name", known.fullName);
            out.put("scheme", known.scheme);
            out.put("valid_to", known.validTo);
        } else if (c.isEmpty() || c.endsWith("0")) {
            out.put("status", "NOT_FOUND");
            out.put("remarks", "No member with this card number");
        } else if (c.endsWith("9")) {
            out.put("status", "INACTIVE");
            out.put("scheme", "Individual");
            out.put("valid_to", nextJune.minusYears(1).toString());
            out.put("remarks", "Cover ended; contributions not renewed");
        } else {
            out.put("status", "ACTIVE");
            out.put("full_name", c.endsWith("8") ? "NEEMA JOHN KIMARO" : null);
            out.put("scheme", "Public servant (dependant)");
            out.put("valid_to", nextJune.toString());
        }
        checks.add(0, Map.of("card", c, "status", String.valueOf(out.get("status")), "at", Instant.now().toString()));
        log.info("[NHIF mock] card {}: {}", c, out.get("status"));
        return ResponseEntity.ok(out);
    }

    @PostMapping(BASE + "/student-registrations")
    public ResponseEntity<Map<String, Object>> register(@RequestHeader(value = "X-Api-Key", required = false) String key, @RequestBody JsonNode body) {
        var refused = switches.refuse("NHIF", apiKey, key);
        if (refused != null) return refused;
        String inst = text(body, "institution_code");
        String year = text(body, "academic_year");
        if (inst == null || year == null || year.length() < 4 || !body.path("students").isArray()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "institution_code, academic_year and students are required"));
        }
        Batch b = new Batch();
        b.batchRef = "NHIF-STU-" + year.substring(0, 4) + "-" + seq.incrementAndGet();
        b.institutionCode = inst;
        b.academicYear = year;
        b.receivedAt = Instant.now().toString();
        b.readyAtEpoch = Instant.now().plusSeconds(processingSeconds).getEpochSecond();
        String validTo = LocalDate.of(Integer.parseInt(year.substring(0, 4)) + 1, 6, 30).toString();
        for (JsonNode s : body.path("students")) {
            b.received++;
            String reg = text(s, "registration_no");
            String name = String.join(" ", nz(text(s, "first_name")), nz(text(s, "middle_name")), nz(text(s, "surname"))).replaceAll("\\s+", " ").trim()
                    .toUpperCase(Locale.ROOT);
            if (text(s, "date_of_birth") == null) {
                b.rejected.add(Map.of("registration_no", String.valueOf(reg), "reason", "Date of birth is required"));
                continue;
            }
            Item i = new Item();
            i.registrationNo = reg;
            i.name = name;
            if (reg != null && reg.endsWith("7")) {
                i.status = "REJECTED";
                i.reason = "Already an NHIF member under another scheme: give the existing card number instead";
            } else {
                i.status = "REGISTERED";
                i.cardNo = String.format("SN%09d", seq.incrementAndGet() * 7919L % 1_000_000_000L);
                i.validTo = validTo;
            }
            b.items.add(i);
        }
        batches.add(0, b);
        log.info("[NHIF mock] {} from {}: {} received, {} rejected on receipt", b.batchRef, inst, b.received, b.rejected.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_ref", b.batchRef);
        out.put("received", b.received);
        out.put("rejected", b.rejected);
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/student-registrations/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                      @RequestParam("institution_code") String inst, @RequestParam("batch_ref") String ref) {
        var refused = switches.refuse("NHIF", apiKey, key);
        if (refused != null) return refused;
        Batch b = batches.stream().filter(x -> x.batchRef.equals(ref) && x.institutionCode.equalsIgnoreCase(inst)).findFirst().orElse(null);
        if (b == null) return ResponseEntity.status(404).body(Map.of("error", "No batch " + ref + " for " + inst));
        boolean ready = Instant.now().getEpochSecond() >= b.readyAtEpoch;
        if (ready) release(b);
        List<Map<String, Object>> items = b.items.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registration_no", i.registrationNo);
            m.put("status", ready ? i.status : "PENDING");
            if (ready) {
                m.put("card_no", i.cardNo);
                m.put("valid_to", i.validTo);
                m.put("reason", i.reason);
            }
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("batch_ref", b.batchRef, "state", ready ? "COMPLETED" : "PROCESSING", "items", items));
    }

    /** Cards issued in a batch become known members, so CIMS can check them later. */
    private void release(Batch b) {
        for (Item i : b.items) {
            if (i.cardNo == null || cards.containsKey(i.cardNo)) continue;
            Card c = new Card();
            c.cardNo = i.cardNo;
            c.status = "ACTIVE";
            c.fullName = i.name;
            c.scheme = "Student package";
            c.validTo = i.validTo;
            c.source = b.batchRef;
            cards.put(c.cardNo, c);
        }
    }

    // --- developer -----------------------------------------------------------------------------------------------------

    @GetMapping("/mock/nhif")
    public Map<String, Object> state() {
        return Map.of("cards", cards.values(), "batches", batches, "checks", checks, "processingSeconds", processingSeconds);
    }

    /** Adds a member card, e.g. a student's own card under a parent's scheme. */
    @PostMapping("/mock/nhif/cards")
    public Card add(@RequestParam String cardNo, @RequestParam String fullName, @RequestParam(defaultValue = "ACTIVE") String status,
                    @RequestParam(required = false) String validTo, @RequestParam(defaultValue = "Public servant (dependant)") String scheme) {
        Card c = new Card();
        c.cardNo = cardNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        c.fullName = fullName.trim().toUpperCase(Locale.ROOT);
        c.status = status.trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        c.validTo = validTo != null && !validTo.isBlank() ? validTo : LocalDate.of(today.getMonthValue() > 6 ? today.getYear() + 1 : today.getYear(), 6, 30).toString();
        c.scheme = scheme;
        c.source = "dashboard";
        cards.put(c.cardNo, c);
        return c;
    }

    @PostMapping("/mock/nhif/reset")
    public ResponseEntity<Void> reset() {
        cards.clear();
        batches.clear();
        checks.clear();
        return ResponseEntity.noContent().build();
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
