package tz.co.msingi.cims.integration.heslb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development HESLB, with the same rules as the gov-mocks stand-in: every student sent whose CSEE index number ends in an even
 * digit is a beneficiary, with tuition of TZS 1,200,000 (0, 2, 4) or 800,000 (6, 8). The first disbursement pays half of each
 * beneficiary's tuition as batch {@code SIM/<year>/B1}, always the same, so fetching again never doubles it.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.heslb", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedHeslbClient implements HeslbClient {

    private final Map<String, Map<String, Beneficiary>> sent = new ConcurrentHashMap<>();   // institution|year -> index -> beneficiary

    @Override
    public SubmissionResult sendRegistrations(String institutionCode, String academicYear, String kind, List<Student> students) {
        Map<String, Beneficiary> map = sent.computeIfAbsent(institutionCode + "|" + academicYear, k -> new ConcurrentHashMap<>());
        List<Rejected> rejected = new ArrayList<>();
        int accepted = 0;
        for (Student s : students) {
            if (s.indexNumber() == null || s.indexNumber().isBlank()) {
                rejected.add(new Rejected(s.registrationNo(), "No CSEE index number: HESLB cannot match the student"));
                continue;
            }
            accepted++;
            char last = s.indexNumber().charAt(s.indexNumber().length() - 1);
            if (Character.isDigit(last) && (last - '0') % 2 == 0) {
                String name = String.join(" ", nz(s.firstName()), nz(s.middleName()), nz(s.surname())).replaceAll("\\s+", " ").trim();
                map.put(s.indexNumber().toUpperCase(), new Beneficiary(s.indexNumber().toUpperCase(), s.registrationNo(), name,
                        "SIM" + academicYear.substring(2, 4) + s.indexNumber().replaceAll("\\D", ""),
                        "024".indexOf(last) >= 0 ? new BigDecimal("1200000") : new BigDecimal("800000")));
            }
        }
        log.info("[HESLB simulated] {} {} for {}: {} of {} accepted", kind, academicYear, institutionCode, accepted, students.size());
        return new SubmissionResult("SIM-HESLB-" + academicYear.substring(0, 4) + "-" + (sent.size() + map.size()), students.size(), accepted, rejected);
    }

    @Override
    public List<Beneficiary> beneficiaries(String institutionCode, String academicYear) {
        return sent.getOrDefault(institutionCode + "|" + academicYear, Map.of()).values().stream()
                .sorted(Comparator.comparing(Beneficiary::indexNumber)).toList();
    }

    @Override
    public List<Disbursement> disbursements(String institutionCode, String academicYear) {
        List<Beneficiary> b = beneficiaries(institutionCode, academicYear);
        if (b.isEmpty()) return List.of();
        List<DisbursedStudent> students = b.stream()
                .map(x -> new DisbursedStudent(x.indexNumber(), x.registrationNo(), x.loanNumber(), x.toInstitution().divide(BigDecimal.valueOf(2)))).toList();
        BigDecimal total = students.stream().map(DisbursedStudent::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return List.of(new Disbursement("SIM/" + academicYear.substring(0, 4) + "/B1", LocalDate.now(), total, students));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
