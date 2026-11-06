package tz.co.msingi.cims.integration.nhif;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NHIF's Student Registration API, simulated inside CIMS (CIMS_NHIF_MODE=simulated, the default) with NHIF's own rules and error
 * codes, so the whole flow can be rehearsed before an institution has NHIF credentials:
 * <pre>
 * Registration
 *   no date of birth                         2070 InvalidDateOfBirth
 *   no Form Four index number                2113 InvalidFormFourIndexNo
 *   admission number ending in 7             2111 MemberAlreadyExists (already a member under another scheme)
 * Card application (all-or-nothing)
 *   batch number not id/startYear/endYear/serial    2124 InvalidBatchNoFormat
 *   category 1 without a NIDA number                2131 NationalIdRequired
 *   category 1 for a NIDA number NHIF does not hold 3020 ReferenceNotFound
 *   category 2 with a card that is not a student's  2139 CardOwnerNotStudent
 *   otherwise a control number (995…) for TZS 50,400 per student
 * Card check (by card number, or NIDA number of a student given a card here)
 *   …0  2137 InvalidCardNo        …9  INACTIVE        …8  ACTIVE, held by NEEMA JOHN KIMARO        other  ACTIVE
 * </pre>
 * Payments and issued cards come from NHIF by callback; when simulated, the Registrar's "simulate" action stands in for them.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nhif", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNhifClient implements NhifClient {

    static final BigDecimal CONTRIBUTION = new BigDecimal("50400.00");
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    /** Students NHIF holds, by NIDA number and by admission number (agent, then key). */
    private final Map<String, Registration> byNin = new ConcurrentHashMap<>();
    private final Map<String, List<ControlNumber>> batches = new ConcurrentHashMap<>();

    @Override
    public BulkResult registerStudents(Agent agent, List<Registration> students) {
        List<Failure> failed = new ArrayList<>();
        for (int i = 0; i < students.size(); i++) {
            Registration s = students.get(i);
            String id = s.formFourIndexNo() != null ? s.formFourIndexNo() : s.admissionNo();
            if (s.dateOfBirth() == null) failed.add(fail(i, id, 2070, "DateOfBirth", "Invalid date of birth."));
            else if (s.formFourIndexNo() == null) failed.add(fail(i, id, 2113, "FormFourIndexNo", "Invalid FormFourIndexNo."));
            else if (s.admissionNo() != null && s.admissionNo().endsWith("7")) {
                failed.add(fail(i, id, 2111, "FirstName", "A member with the same first name, last name, date of birth, and gender already exists."));
            } else if (s.nationalId() != null) {
                byNin.put(agent.clientId() + "|" + s.nationalId(), s);
            }
        }
        log.info("[NHIF simulated] {} registered {} of {} student(s)", agent.clientId(), students.size() - failed.size(), students.size());
        return new BulkResult(students.size() - failed.size(), failed, students.size());
    }

    @Override
    public BulkResult applyForCards(Agent agent, String batchNo, String intakeCode, String description, List<CardApplicationItem> items) {
        List<Failure> failed = new ArrayList<>();
        if (batchNo == null || !batchNo.matches("[A-Za-z0-9]+/(\\d{4})/(\\d{4})/.+")
                || Integer.parseInt(batchNo.split("/")[2]) != Integer.parseInt(batchNo.split("/")[1]) + 1) {
            failed.add(fail(0, batchNo, 2124, "BatchNo", "BatchNo must be in the format id/startYear/endYear/serial (e.g. 5464646/2024/2025/43535-EXC-6464)."));
        }
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CardApplicationItem it = items.get(i);
            if (!seen.add(it.correlationId())) failed.add(fail(i, it.correlationId(), 3017, "CorrelationID", "Duplicate CorrelationID found in the request."));
            else if (it.category() == 1 && it.nationalId() == null) failed.add(fail(i, it.correlationId(), 2131, "NationalID", "NationalID is required."));
            else if (it.category() == 1 && !byNin.containsKey(agent.clientId() + "|" + it.nationalId())) {
                failed.add(fail(i, it.correlationId(), 3020, "NationalID", "Referenced record was not found."));
            } else if (it.category() == 2 && (it.cardNo() == null || !it.cardNo().startsWith("SN"))) {
                failed.add(fail(i, it.correlationId(), 2139, "CardNo", "The owner of this card is not a student."));
            }
        }
        if (!failed.isEmpty()) return new BulkResult(0, failed, items.size());   // all-or-nothing, as NHIF does
        List<ControlNumber> out = new ArrayList<>();
        for (CardApplicationItem it : items) {
            String cn = String.format("995%09d", Math.floorMod((batchNo + it.correlationId()).hashCode(), 1_000_000_000));
            out.add(new ControlNumber(it.correlationId(), it.cardNo(), batchNo, it.mobileNo(), "INV-" + cn.substring(5), cn, CONTRIBUTION, it.category(), null));
        }
        batches.put(batchNo, out);
        log.info("[NHIF simulated] card application {}: {} item(s)", batchNo, items.size());
        return new BulkResult(items.size(), List.of(), items.size());
    }

    @Override
    public CardCheck verifyStudentCard(Agent agent, String cardNoOrNin) {
        String raw = cardNoOrNin == null ? "" : cardNoOrNin.trim();
        if (raw.matches("\\d{20}")) {
            Registration r = byNin.get(agent.clientId() + "|" + raw);
            return r == null ? new CardCheck(raw, null, null, null, null, new Error(2137, "cardNo", "CardNo does not exist or is not valid."))
                    : new CardCheck(raw, name(r), "ACTIVE", -1, "V" + raw.substring(12), null);
        }
        String c = NhifClient.normalise(raw);
        if (c == null) return new CardCheck(raw, null, null, null, null, new Error(2137, "cardNo", "CardNo does not exist or is not valid."));
        String code = c + "F3A";
        return switch (c.charAt(c.length() - 1)) {
            case '0' -> new CardCheck(c, null, null, null, null, new Error(2137, "cardNo", "CardNo does not exist or is not valid."));
            case '9' -> new CardCheck(c, null, "INACTIVE", 0, code, null);
            case '8' -> new CardCheck(c, "NEEMA JOHN KIMARO", "ACTIVE", -1, code, null);
            default -> new CardCheck(c, null, "ACTIVE", -1, code, null);
        };
    }

    @Override
    public List<Intake> intakes(Agent agent) {
        LocalDate today = LocalDate.now(ZONE);
        int y = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        return List.of(new Intake("October " + y + " Intake", NhifClient.intakeCode(y, 10), LocalDate.of(y, 10, 1), LocalDate.of(y, 10, 31)),
                new Intake("March " + (y + 1) + " Intake", NhifClient.intakeCode(y + 1, 3), LocalDate.of(y + 1, 3, 1), LocalDate.of(y + 1, 3, 31)));
    }

    @Override
    public List<ControlNumber> controlNumbers(Agent agent, String batchNo) {
        return batches.getOrDefault(batchNo, List.of());
    }

    @Override
    public BulkResult changeRequest(Agent agent, int category, String cardNo, String academicYear, String formFourIndexNo) {
        return new BulkResult(1, List.of(), 1);
    }

    private static Failure fail(int index, String id, int code, String field, String message) {
        return new Failure(index, id, List.of(new Error(code, field, message)));
    }

    private static String name(Registration r) {
        return String.join(" ", r.firstName(), r.middleName() == null ? "" : r.middleName(), r.surname()).replaceAll("\\s+", " ").trim().toUpperCase();
    }
}
