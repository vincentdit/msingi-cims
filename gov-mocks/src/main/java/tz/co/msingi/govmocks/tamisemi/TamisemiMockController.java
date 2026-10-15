package tz.co.msingi.govmocks.tamisemi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.govmocks.common.MockSwitches;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TAMISEMI's side (FR-INT-08): the students it selected to an institution's programmes for a year. The developer adds
 * selections on the dashboard (random names and CSEE index numbers of the previous year), or one with a chosen index number,
 * e.g. one NECTA knows, so CIMS's NECTA look-up finds it.
 */
@RestController
public class TamisemiMockController {

    private static final String[] FIRST_F = {"Asha", "Neema", "Rehema", "Zawadi", "Upendo", "Mwanaidi", "Faraja", "Halima"};
    private static final String[] FIRST_M = {"Juma", "Baraka", "Emmanuel", "Hamisi", "Daudi", "Salum", "Goodluck", "Rashidi"};
    private static final String[] SURNAMES = {"Mwakyusa", "Kimaro", "Massawe", "Mrema", "Mushi", "Lyimo", "Ngowi", "Mbwambo", "Shirima", "Komba"};

    private final MockSwitches switches;
    private final String apiKey;
    private final List<Map<String, Object>> selections = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger seq = new AtomicInteger(1);

    public TamisemiMockController(MockSwitches switches, @Value("${mock.tamisemi.api-key}") String apiKey) {
        this.switches = switches;
        this.apiKey = apiKey;
    }

    @GetMapping("/tamisemi/api/v1/selections")
    public ResponseEntity<Map<String, Object>> list(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                    @RequestParam("institution_code") String inst, @RequestParam int year) {
        var refused = switches.refuse("TAMISEMI", apiKey, key);
        if (refused != null) return refused;
        List<Map<String, Object>> list = selections.stream()
                .filter(s -> inst.equalsIgnoreCase((String) s.get("institution_code")) && Integer.valueOf(year).equals(s.get("selection_year")))
                .toList();
        return ResponseEntity.ok(Map.of("institution_code", inst, "year", year, "selections", list));
    }

    @GetMapping("/mock/tamisemi")
    public Map<String, Object> state() {
        return Map.of("selections", selections);
    }

    /** Adds selections: {@code count} random students, or one with the given index number and names. */
    @PostMapping("/mock/tamisemi/selections")
    public List<Map<String, Object>> add(@RequestParam String institutionCode, @RequestParam String programmeCode,
                                         @RequestParam(defaultValue = "0") int year, @RequestParam(defaultValue = "5") int count,
                                         @RequestParam(required = false) String indexNumber, @RequestParam(required = false) Integer examYear,
                                         @RequestParam(required = false) String firstName, @RequestParam(required = false) String surname,
                                         @RequestParam(required = false) String sex) {
        int y = year > 0 ? year : LocalDate.now().getYear();
        int n = indexNumber != null && !indexNumber.isBlank() ? 1 : Math.max(1, Math.min(count, 200));
        List<Map<String, Object>> added = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean female = sex != null ? sex.equalsIgnoreCase("F") : random.nextBoolean();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("institution_code", institutionCode.trim().toUpperCase());
            s.put("selection_year", y);
            s.put("index_number", indexNumber != null && !indexNumber.isBlank() ? indexNumber.trim().toUpperCase()
                    : String.format("S%04d/%04d", 100 + random.nextInt(5000), 1 + random.nextInt(400)));
            s.put("exam_year", examYear != null ? examYear : y - 1);
            s.put("first_name", firstName != null && !firstName.isBlank() ? firstName.trim() : pick(female ? FIRST_F : FIRST_M));
            s.put("middle_name", pick(female ? FIRST_F : FIRST_M));
            s.put("surname", surname != null && !surname.isBlank() ? surname.trim() : pick(SURNAMES));
            s.put("sex", female ? "F" : "M");
            s.put("date_of_birth", LocalDate.of(y - 18 - random.nextInt(3), 1 + random.nextInt(12), 1 + random.nextInt(28)).toString());
            s.put("nationality", "Tanzanian");
            s.put("phone", "07" + (10 + random.nextInt(80)) + String.format("%06d", random.nextInt(1_000_000)));
            s.put("programme_code", programmeCode.trim().toUpperCase());
            s.put("reference", "TAMISEMI/" + y + "/" + String.format("%05d", seq.getAndIncrement()));
            selections.add(s);
            added.add(s);
        }
        return added;
    }

    @PostMapping("/mock/tamisemi/reset")
    public ResponseEntity<Void> reset() {
        selections.clear();
        return ResponseEntity.noContent().build();
    }

    private String pick(String[] a) {
        return a[random.nextInt(a.length)];
    }
}
