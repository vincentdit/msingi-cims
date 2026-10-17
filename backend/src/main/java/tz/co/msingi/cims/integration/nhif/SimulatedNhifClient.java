package tz.co.msingi.cims.integration.nhif;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development NHIF, with the same rules as the gov-mocks stand-in, so a demo always behaves the same way.
 * <pre>
 * Cards, by the last digit of the card number:
 *   …0  NOT_FOUND
 *   …9  INACTIVE (cover ended on 30 June)
 *   …8  ACTIVE, but held by someone else (NEEMA JOHN KIMARO)
 *   other  ACTIVE until the next 30 June, holder's name not given
 * Registration under the student package:
 *   no date of birth                       rejected straight away
 *   registration number ending in 7        REJECTED: already a member under another scheme
 *   otherwise                              REGISTERED with a new card, valid to 30 June at the end of the academic year
 * </pre>
 * Cards issued here are ACTIVE when checked later.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nhif", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNhifClient implements NhifClient {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final Map<String, List<Item>> batches = new ConcurrentHashMap<>();
    private final Map<String, Card> issued = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Override
    public Card verifyCard(String cardNo) {
        String c = NhifClient.normalise(cardNo);
        if (c == null) return new Card(cardNo, "NOT_FOUND", null, null, null, "Not an NHIF card number");
        Card known = issued.get(c);
        if (known != null) return known;
        LocalDate today = LocalDate.now(ZONE);
        LocalDate nextJune = LocalDate.of(today.getMonthValue() > 6 ? today.getYear() + 1 : today.getYear(), 6, 30);
        return switch (c.charAt(c.length() - 1)) {
            case '0' -> new Card(c, "NOT_FOUND", null, null, null, "No member with this card number");
            case '9' -> new Card(c, "INACTIVE", null, "Individual", nextJune.minusYears(1), "Cover ended; contributions not renewed");
            case '8' -> new Card(c, "ACTIVE", "NEEMA JOHN KIMARO", "Public servant (dependant)", nextJune, null);
            default -> new Card(c, "ACTIVE", null, "Public servant (dependant)", nextJune, null);
        };
    }

    @Override
    public Submission registerStudents(String institutionCode, String academicYear, List<Student> students) {
        String ref = "SIM-NHIF-" + academicYear.substring(0, 4) + "-" + seq.incrementAndGet();
        LocalDate validTo = LocalDate.of(Integer.parseInt(academicYear.substring(0, 4)) + 1, 6, 30);
        List<Rejected> rejected = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (Student s : students) {
            if (s.dateOfBirth() == null) {
                rejected.add(new Rejected(s.registrationNo(), "Date of birth is required"));
            } else if (s.registrationNo() != null && s.registrationNo().endsWith("7")) {
                items.add(new Item(s.registrationNo(), "REJECTED", null, null,
                        "Already an NHIF member under another scheme: give the existing card number instead"));
            } else {
                String card = String.format("SN%09d", Math.floorMod((institutionCode + s.registrationNo()).hashCode(), 1_000_000_000));
                String name = String.join(" ", nz(s.firstName()), nz(s.middleName()), nz(s.surname())).replaceAll("\\s+", " ").trim().toUpperCase();
                issued.put(card, new Card(card, "ACTIVE", name, "Student package", validTo, null));
                items.add(new Item(s.registrationNo(), "REGISTERED", card, validTo, null));
            }
        }
        batches.put(ref, items);
        log.info("[NHIF simulated] {} for {} {}: {} student(s), {} rejected on receipt", ref, institutionCode, academicYear, students.size(), rejected.size());
        return new Submission(ref, students.size(), rejected);
    }

    @Override
    public Status registrationStatus(String institutionCode, String batchRef) {
        List<Item> items = batches.get(batchRef);
        return items == null ? new Status("COMPLETED", List.of()) : new Status("COMPLETED", items);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
