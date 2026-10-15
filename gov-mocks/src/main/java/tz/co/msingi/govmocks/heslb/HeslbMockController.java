package tz.co.msingi.govmocks.heslb;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HESLB's side (FR-INT-05), in memory. The institution sends its registration (first year) and continuation (later years)
 * data; HESLB answers with its beneficiaries for the year and the disbursement batches it has paid to the institution.
 * <p>
 * Who is a beneficiary: every registered student whose CSEE index number ends in an even digit (0, 2, 4, 6, 8), plus anyone
 * the developer adds on the dashboard. Tuition is TZS 1,200,000 (index ending 0, 2 or 4) or 800,000 (6 or 8), paid to the
 * institution; meals and accommodation and books are paid to the student and only listed. A disbursement made on the
 * dashboard pays half of each beneficiary's tuition.
 */
@RestController
public class HeslbMockController {

    private static final Logger log = LoggerFactory.getLogger(HeslbMockController.class);
    private static final String BASE = "/heslb/api/v1";

    public static final class Beneficiary {
        public String institutionCode, academicYear, indexNumber, registrationNo, fullName, loanNumber, source;
        public BigDecimal tuition;
        public BigDecimal disbursed = BigDecimal.ZERO;
    }

    public static final class Disbursement {
        public String batchNo, institutionCode, academicYear, disbursedOn;
        public BigDecimal amount = BigDecimal.ZERO;
        public List<Map<String, Object>> students = new ArrayList<>();
    }

    public static final class Submission {
        public String ref, institutionCode, academicYear, kind, receivedAt;
        public int received, accepted;
        public List<Map<String, String>> rejected = new ArrayList<>();
    }

    private final Map<String, Beneficiary> beneficiaries = new ConcurrentHashMap<>();   // institution|year|index -> beneficiary
    private final List<Disbursement> disbursements = new CopyOnWriteArrayList<>();
    private final List<Submission> submissions = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(100);
    private final MockSwitches switches;
    private final String apiKey;

    public HeslbMockController(MockSwitches switches, @Value("${mock.heslb.api-key}") String apiKey) {
        this.switches = switches;
        this.apiKey = apiKey;
    }

    /** Registration or continuation data. Students without a CSEE index number cannot be matched and are rejected. */
    @PostMapping(BASE + "/registrations")
    public ResponseEntity<Map<String, Object>> registrations(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                             @RequestBody JsonNode body) {
        var refused = switches.refuse("HESLB", apiKey, key);
        if (refused != null) return refused;
        String inst = text(body, "institution_code");
        String year = text(body, "academic_year");
        String kind = text(body, "kind");
        if (inst == null || year == null || !body.path("students").isArray()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "institution_code, academic_year and students are required"));
        }
        Submission s = new Submission();
        s.ref = "HESLB-REG-" + year.substring(0, 4) + "-" + seq.incrementAndGet();
        s.institutionCode = inst;
        s.academicYear = year;
        s.kind = kind == null ? "REGISTRATION" : kind;
        s.receivedAt = java.time.OffsetDateTime.now().toString();
        for (JsonNode st : body.path("students")) {
            s.received++;
            String index = text(st, "index_number");
            String reg = text(st, "registration_no");
            if (index == null) {
                s.rejected.add(Map.of("registration_no", String.valueOf(reg), "reason", "No CSEE index number: HESLB cannot match the student"));
                continue;
            }
            s.accepted++;
            char last = index.charAt(index.length() - 1);
            if (Character.isDigit(last) && (last - '0') % 2 == 0) {
                String k = inst + "|" + year + "|" + index.toUpperCase();
                Beneficiary b = beneficiaries.computeIfAbsent(k, x -> {
                    Beneficiary n = new Beneficiary();
                    n.institutionCode = inst;
                    n.academicYear = year;
                    n.indexNumber = index.toUpperCase();
                    n.loanNumber = "LN" + year.substring(2, 4) + String.format("%06d", seq.incrementAndGet());
                    n.tuition = "024".indexOf(last) >= 0 ? new BigDecimal("1200000") : new BigDecimal("800000");
                    n.source = "registration";
                    return n;
                });
                b.registrationNo = reg;
                b.fullName = String.join(" ", nz(text(st, "first_name")), nz(text(st, "middle_name")), nz(text(st, "surname"))).replaceAll("\\s+", " ").trim();
            }
        }
        submissions.add(0, s);
        log.info("[HESLB mock] {} {}: {} received, {} accepted", s.ref, s.kind, s.received, s.accepted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submission_ref", s.ref);
        out.put("received", s.received);
        out.put("accepted", s.accepted);
        out.put("rejected", s.rejected);
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/beneficiaries")
    public ResponseEntity<Map<String, Object>> beneficiaries(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                             @RequestParam("institution_code") String inst, @RequestParam("academic_year") String year) {
        var refused = switches.refuse("HESLB", apiKey, key);
        if (refused != null) return refused;
        List<Map<String, Object>> list = beneficiaries.values().stream().filter(b -> b.institutionCode.equalsIgnoreCase(inst) && b.academicYear.equals(year))
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index_number", b.indexNumber);
                    m.put("registration_no", b.registrationNo);
                    m.put("full_name", b.fullName);
                    m.put("loan_number", b.loanNumber);
                    m.put("allocations", List.of(
                            Map.of("item", "TUITION_FEE", "amount", b.tuition, "payable_to", "INSTITUTION"),
                            Map.of("item", "MEALS_AND_ACCOMMODATION", "amount", new BigDecimal("1530000"), "payable_to", "STUDENT"),
                            Map.of("item", "BOOKS_AND_STATIONERY", "amount", new BigDecimal("200000"), "payable_to", "STUDENT")));
                    m.put("total_to_institution", b.tuition);
                    return m;
                }).toList();
        return ResponseEntity.ok(Map.of("institution_code", inst, "academic_year", year, "beneficiaries", list));
    }

    @GetMapping(BASE + "/disbursements")
    public ResponseEntity<Map<String, Object>> disbursements(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                             @RequestParam("institution_code") String inst, @RequestParam("academic_year") String year) {
        var refused = switches.refuse("HESLB", apiKey, key);
        if (refused != null) return refused;
        List<Map<String, Object>> list = disbursements.stream().filter(d -> d.institutionCode.equalsIgnoreCase(inst) && d.academicYear.equals(year))
                .map(d -> Map.<String, Object>of("batch_no", d.batchNo, "disbursed_on", d.disbursedOn, "amount", d.amount, "students", d.students)).toList();
        return ResponseEntity.ok(Map.of("institution_code", inst, "academic_year", year, "disbursements", list));
    }

    // --- developer -----------------------------------------------------------------------------------------------------

    @GetMapping("/mock/heslb")
    public Map<String, Object> state() {
        return Map.of("beneficiaries", beneficiaries.values(), "disbursements", disbursements, "submissions", submissions);
    }

    @PostMapping("/mock/heslb/beneficiaries")
    public Beneficiary add(@RequestParam String institutionCode, @RequestParam String academicYear, @RequestParam String indexNumber,
                           @RequestParam(required = false) String registrationNo, @RequestParam(required = false) String fullName,
                           @RequestParam(defaultValue = "1200000") BigDecimal tuition) {
        Beneficiary b = new Beneficiary();
        b.institutionCode = institutionCode.trim().toUpperCase();
        b.academicYear = academicYear.trim();
        b.indexNumber = indexNumber.trim().toUpperCase();
        b.registrationNo = registrationNo;
        b.fullName = fullName;
        b.tuition = tuition;
        b.loanNumber = "LN" + b.academicYear.substring(2, 4) + String.format("%06d", seq.incrementAndGet());
        b.source = "dashboard";
        beneficiaries.put(b.institutionCode + "|" + b.academicYear + "|" + b.indexNumber, b);
        return b;
    }

    /** Pays half of each beneficiary's tuition (never more than is left) to the institution as one batch. */
    @PostMapping("/mock/heslb/disburse")
    public ResponseEntity<?> disburse(@RequestParam String institutionCode, @RequestParam String academicYear) {
        Disbursement d = new Disbursement();
        d.batchNo = "HESLB/" + academicYear.substring(0, 4) + "/B" + seq.incrementAndGet();
        d.institutionCode = institutionCode.trim().toUpperCase();
        d.academicYear = academicYear.trim();
        d.disbursedOn = LocalDate.now().toString();
        for (Beneficiary b : beneficiaries.values()) {
            if (!b.institutionCode.equalsIgnoreCase(d.institutionCode) || !b.academicYear.equals(d.academicYear)) continue;
            BigDecimal left = b.tuition.subtract(b.disbursed);
            BigDecimal amt = b.tuition.divide(BigDecimal.valueOf(2)).min(left);
            if (amt.signum() <= 0) continue;
            b.disbursed = b.disbursed.add(amt);
            d.amount = d.amount.add(amt);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_number", b.indexNumber);
            m.put("registration_no", b.registrationNo);
            m.put("loan_number", b.loanNumber);
            m.put("item", "TUITION_FEE");
            m.put("amount", amt);
            d.students.add(m);
        }
        if (d.students.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Nothing left to disburse for " + d.institutionCode + " " + d.academicYear));
        disbursements.add(0, d);
        log.info("[HESLB mock] {} pays {} for {} student(s) to {}", d.batchNo, d.amount, d.students.size(), d.institutionCode);
        return ResponseEntity.ok(d);
    }

    @PostMapping("/mock/heslb/reset")
    public ResponseEntity<Void> reset() {
        beneficiaries.clear();
        disbursements.clear();
        submissions.clear();
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
