package tz.co.msingi.nactvetmock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NACTVET's side of admission verification, in memory. Outcomes are decided when a batch arrives and released once
 * processing time has passed. By the last digit of the candidate's CSEE index number (the same rules as CIMS's built-in
 * simulator, so demos behave alike):
 * <pre>
 *   …7  QUERIED       name does not match NECTA; verified once resubmitted with remarks
 *   …9  NOT_VERIFIED  does not meet the minimum entry requirements
 *   …3  VERIFIED      also admitted by another institution: NACTVET sends the applicant a confirmation code
 *   …5  VERIFIED      but takes three times as long to process
 *   any other digit  VERIFIED
 * </pre>
 * A candidate without names, date of birth or programme code is QUERIED, as is the same index number twice in one batch.
 * An application already decided in an earlier batch gets the same outcome again (NACTVET does not decide twice).
 */
@Component
public class VerificationStore {

    public enum Status { PROCESSING, VERIFIED, QUERIED, NOT_VERIFIED }

    public record Candidate(String applicationRef, String indexNumber, Integer examYear, String firstName, String middleName,
                            String surname, String sex, String dateOfBirth, String nationality, String phone,
                            String programmeCode, String programmeName, Integer awardLevel, String remarks) {
    }

    public record Decision(Status status, String reason, String verificationNumber, boolean multipleAdmission) {
    }

    public record Item(Candidate candidate, Decision decision, Instant readyAt) {
    }

    public record Batch(String batchRef, String institutionCode, Instant receivedAt, List<Item> items) {
    }

    /** A multiple admission: the institutions that admitted the applicant, the code NACTVET sent and the one confirmed. */
    public static final class Confirmation {
        final String code;
        final Set<String> admittedBy = new HashSet<>();
        String confirmedBy;

        Confirmation(String code) {
            this.code = code;
        }
    }

    public enum ConfirmResult { CONFIRMED, WRONG_CODE, NOT_ADMITTED, ALREADY_CONFIRMED_ELSEWHERE }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final Map<String, Decision> decided = new ConcurrentHashMap<>();          // institution + application -> final decision
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>(); // index number -> multiple admission
    private final AtomicInteger batchSeq = new AtomicInteger();
    private final AtomicInteger verificationSeq = new AtomicInteger();
    private final long processingSeconds;
    private volatile Clock clock = Clock.systemUTC();
    private volatile boolean outage;

    public VerificationStore(@Value("${mock.processing-seconds:5}") long processingSeconds) {
        this.processingSeconds = processingSeconds;
    }

    public synchronized Batch submit(String institutionCode, List<Candidate> candidates) {
        Instant now = clock.instant();
        String ref = "NV-" + institutionCode + "-" + Year.now(clock).getValue() + "-" + String.format("%05d", batchSeq.incrementAndGet());
        Set<String> seen = new HashSet<>();
        List<Item> items = new ArrayList<>();
        for (Candidate c : candidates) {
            String index = c.indexNumber() == null ? "" : c.indexNumber().trim().toUpperCase();
            int digit = lastDigit(index);
            Decision d = decide(institutionCode, c, index, digit, seen);
            long seconds = digit == 5 ? processingSeconds * 3 : processingSeconds;
            items.add(new Item(c, d, now.plusSeconds(seconds)));
        }
        Batch b = new Batch(ref, institutionCode, now, List.copyOf(items));
        batches.put(ref, b);
        return b;
    }

    private Decision decide(String institution, Candidate c, String index, int digit, Set<String> seen) {
        String key = institution + "|" + c.applicationRef();
        Decision earlier = decided.get(key);
        if (earlier != null && earlier.status() != Status.QUERIED) return earlier;
        Decision d;
        String missing = missing(c);
        if (!seen.add(index)) {
            d = new Decision(Status.QUERIED, "The same index number appears more than once in this batch", null, false);
        } else if (missing != null) {
            d = new Decision(Status.QUERIED, "Missing " + missing + " in the candidate's record", null, false);
        } else if (digit == 7 && (c.remarks() == null || c.remarks().isBlank())) {
            d = new Decision(Status.QUERIED, "Candidate name does not match NECTA records for this index number", null, false);
        } else if (digit == 9) {
            d = new Decision(Status.NOT_VERIFIED, "Does not meet NACTVET minimum entry requirements for " + c.programmeCode(), null, false);
        } else {
            boolean multiple = digit == 3;
            if (multiple) {
                Confirmation conf = confirmations.computeIfAbsent(index, k -> new Confirmation(String.format("%06d", RANDOM.nextInt(1_000_000))));
                conf.admittedBy.add(institution);
                conf.admittedBy.add("OTHER-INSTITUTION");   // the other institution that admitted the applicant
            }
            d = new Decision(Status.VERIFIED, null, String.format("NV/%d/%06d", Year.now(clock).getValue(), verificationSeq.incrementAndGet()), multiple);
        }
        decided.put(key, d);
        return d;
    }

    private static String missing(Candidate c) {
        List<String> m = new ArrayList<>();
        if (blank(c.applicationRef())) m.add("application reference");
        if (blank(c.indexNumber())) m.add("index number");
        if (blank(c.firstName()) || blank(c.surname())) m.add("names");
        if (blank(c.dateOfBirth())) m.add("date of birth");
        if (blank(c.programmeCode())) m.add("programme code");
        return m.isEmpty() ? null : String.join(", ", m);
    }

    public Optional<Batch> batch(String ref) {
        return Optional.ofNullable(batches.get(ref));
    }

    /** What NACTVET can say about a batch now: decisions whose processing time has passed, PROCESSING for the rest. */
    public List<Map<String, Object>> outcomes(Batch b) {
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : b.items()) {
            boolean ready = !now.isBefore(i.readyAt());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("application_ref", i.candidate().applicationRef());
            m.put("index_number", i.candidate().indexNumber());
            m.put("programme_code", i.candidate().programmeCode());
            m.put("status", ready ? i.decision().status().name() : Status.PROCESSING.name());
            if (ready) {
                m.put("reason", i.decision().reason());
                m.put("verification_number", i.decision().verificationNumber());
                m.put("multiple_admission", i.decision().multipleAdmission() ? 1 : 0);
            }
            out.add(m);
        }
        return out;
    }

    public synchronized ConfirmResult confirm(String institution, String indexNumber, String code) {
        Confirmation c = confirmations.get(indexNumber == null ? "" : indexNumber.trim().toUpperCase());
        if (c == null || !c.admittedBy.contains(institution)) return ConfirmResult.NOT_ADMITTED;
        if (c.confirmedBy != null && !c.confirmedBy.equals(institution)) return ConfirmResult.ALREADY_CONFIRMED_ELSEWHERE;
        if (code == null || !c.code.equals(code.trim())) return ConfirmResult.WRONG_CODE;
        c.confirmedBy = institution;
        return ConfirmResult.CONFIRMED;
    }

    /** The code NACTVET "sent by SMS", for the developer (there is no real SMS from the mock). */
    public Optional<String> confirmationCode(String indexNumber) {
        Confirmation c = confirmations.get(indexNumber == null ? "" : indexNumber.trim().toUpperCase());
        return c == null ? Optional.empty() : Optional.of(c.code);
    }

    /** For the developer: another institution confirms first, so this institution's confirmation is refused. */
    public synchronized boolean confirmElsewhere(String indexNumber) {
        Confirmation c = confirmations.get(indexNumber == null ? "" : indexNumber.trim().toUpperCase());
        if (c == null) return false;
        c.confirmedBy = "OTHER-INSTITUTION";
        return true;
    }

    public List<Batch> batches() {
        return batches.values().stream().sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt())).toList();
    }

    public synchronized void reset() {
        batches.clear();
        decided.clear();
        confirmations.clear();
        outage = false;
    }

    public boolean outage() {
        return outage;
    }

    public void setOutage(boolean on) {
        this.outage = on;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    static int lastDigit(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isDigit(s.charAt(i))) return s.charAt(i) - '0';
        }
        return 0;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
