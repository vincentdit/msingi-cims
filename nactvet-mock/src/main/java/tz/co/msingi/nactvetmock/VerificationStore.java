package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * NACTVET's side of admission verification, in memory. Applicants arrive by upload, one programme per heading (as NOAS
 * uploads them); outcomes are decided on arrival and published in the verification feedback once processing time has passed.
 * By the last digit of the applicant's form four index number (the same rules as CIMS's built-in simulator, so demos
 * behave alike):
 * <pre>
 *   …7  QUERIED       name does not match NECTA; verified when uploaded again
 *   …9  NOT_VERIFIED  does not meet the minimum entry requirements
 *   …3  VERIFIED      also admitted by another institution: NACTVET sends the applicant a confirmation code
 *   …5  VERIFIED      but takes three times as long to process
 *   any other digit  VERIFIED
 * </pre>
 * An applicant without names, date of birth or form four index number is QUERIED, as is the same index number twice in one
 * upload. An applicant already decided in an earlier upload for the same programme gets the same outcome again.
 * <p>
 * Everything is saved to {@code mock.state-file} after each change and read back at start-up, so restarting the mock does
 * not forget what CIMS is still waiting on (as the real NACTVET would not). An empty setting keeps it in memory only.
 */
@Component
public class VerificationStore {

    public enum Status { PROCESSING, VERIFIED, QUERIED, NOT_VERIFIED }

    /** One applicant's particulars, as uploaded (only what the mock looks at). */
    public record Candidate(String indexNumber, String examYear, String firstName, String secondName, String surname,
                            String dateOfBirth, String gender, String impairment, String phone) {
    }

    /** The heading of an upload: one programme in one intake, under the payment reference of the verification fee. */
    public record Heading(String intake, String programmeId, String applicationYear, String level, String paymentReference) {
    }

    public record Decision(Status status, String reason, String verificationNumber, boolean multipleAdmission) {
    }

    public record Item(Candidate candidate, Decision decision, Instant readyAt) {
    }

    public record Batch(String batchRef, String institutionCode, Instant receivedAt, Heading heading, List<Item> items) {
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

    /** What is saved to the state file. */
    record State(List<Batch> batches, Map<String, Decision> decided, Map<String, ConfirmationState> confirmations, int batchSeq, int verificationSeq) {
    }

    record ConfirmationState(String code, Set<String> admittedBy, String confirmedBy) {
    }

    public enum ConfirmResult { CONFIRMED, WRONG_CODE, NOT_ADMITTED, ALREADY_CONFIRMED_ELSEWHERE }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(VerificationStore.class);

    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final Map<String, Decision> decided = new ConcurrentHashMap<>();          // institution + application -> final decision
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>(); // index number -> multiple admission
    private final AtomicInteger batchSeq = new AtomicInteger();
    private final AtomicInteger verificationSeq = new AtomicInteger();
    private final long processingSeconds;
    private volatile Clock clock = Clock.systemUTC();
    private volatile boolean outage;

    private final ObjectMapper json;
    private final Path stateFile;

    public VerificationStore(@Value("${mock.processing-seconds:5}") long processingSeconds,
                             @Value("${mock.state-file:}") String stateFile, ObjectMapper json) {
        this.processingSeconds = processingSeconds;
        this.json = json;
        this.stateFile = stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile).toAbsolutePath();
        load();
    }

    private synchronized void load() {
        if (stateFile == null || !Files.exists(stateFile)) return;
        try {
            State st = json.readValue(stateFile.toFile(), State.class);
            st.batches().forEach(b -> batches.put(b.batchRef(), b));
            decided.putAll(st.decided());
            st.confirmations().forEach((k, v) -> {
                Confirmation c = new Confirmation(v.code());
                c.admittedBy.addAll(v.admittedBy());
                c.confirmedBy = v.confirmedBy();
                confirmations.put(k, c);
            });
            batchSeq.set(st.batchSeq());
            verificationSeq.set(st.verificationSeq());
            log.info("[NACTVET mock] {} batch(es) read back from {}", batches.size(), stateFile);
        } catch (IOException e) {
            log.warn("[NACTVET mock] could not read {} ({}); starting empty", stateFile, e.getMessage());
        }
    }

    private synchronized void save() {
        if (stateFile == null) return;
        Map<String, ConfirmationState> conf = new LinkedHashMap<>();
        confirmations.forEach((k, c) -> conf.put(k, new ConfirmationState(c.code, Set.copyOf(c.admittedBy), c.confirmedBy)));
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),
                    new State(List.copyOf(batches.values()), Map.copyOf(decided), conf, batchSeq.get(), verificationSeq.get()));
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("[NACTVET mock] could not save {}: {}", stateFile, e.getMessage());
        }
    }

    public synchronized Batch upload(String institutionCode, Heading heading, List<Candidate> candidates) {
        Instant now = clock.instant();
        String ref = "UP-" + Year.now(clock).getValue() + "-" + String.format("%05d", batchSeq.incrementAndGet());
        Set<String> seen = new HashSet<>();
        List<Item> items = new ArrayList<>();
        for (Candidate c : candidates) {
            String index = c.indexNumber() == null ? "" : c.indexNumber().trim().toUpperCase();
            int digit = lastDigit(index);
            Decision d = decide(institutionCode, heading, c, index, digit, seen);
            long seconds = digit == 5 ? processingSeconds * 3 : processingSeconds;
            items.add(new Item(c, d, now.plusSeconds(seconds)));
        }
        Batch b = new Batch(ref, institutionCode, now, heading, List.copyOf(items));
        batches.put(ref, b);
        save();
        return b;
    }

    private Decision decide(String institution, Heading heading, Candidate c, String index, int digit, Set<String> seen) {
        String key = institution + "|" + heading.programmeId() + "|" + index;
        if (!seen.add(index)) {   // a duplicate row in one upload is queried and leaves any earlier decision as it was
            return new Decision(Status.QUERIED, "The same index number appears more than once in this upload", null, false);
        }
        Decision earlier = decided.get(key);
        if (earlier != null && earlier.status() != Status.QUERIED) return earlier;
        boolean uploadedAgain = earlier != null;
        Decision d;
        String missing = missing(c);
        if (missing != null) {
            d = new Decision(Status.QUERIED, "Missing " + missing + " in the applicant's particulars", null, false);
        } else if (digit == 7 && !uploadedAgain) {
            d = new Decision(Status.QUERIED, "Applicant name does not match NECTA records for this index number", null, false);
        } else if (digit == 9) {
            d = new Decision(Status.NOT_VERIFIED, "Does not meet NACTVET minimum entry requirements for programme " + heading.programmeId(), null, false);
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
        if (blank(c.indexNumber())) m.add("form four index number");
        if (blank(c.firstName()) || blank(c.surname())) m.add("names");
        if (blank(c.dateOfBirth())) m.add("date of birth");
        return m.isEmpty() ? null : String.join(", ", m);
    }

    public Optional<Batch> batch(String ref) {
        return Optional.ofNullable(batches.get(ref));
    }

    /** What NACTVET can say about an upload now: decisions whose processing time has passed, PROCESSING for the rest. */
    public List<Map<String, Object>> outcomes(Batch b) {
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : b.items()) {
            boolean ready = !now.isBefore(i.readyAt());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_number", i.candidate().indexNumber());
            m.put("name", String.join(" ", nz(i.candidate().firstName()), nz(i.candidate().surname())).trim());
            m.put("programme_id", b.heading().programmeId());
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

    /**
     * NACTVET's verification feedback for an application year and intake, one row per applicant and programme (the latest
     * upload wins), in the shape of NACTVET's {@code verifiedresultsall}.
     */
    public List<Map<String, Object>> feedback(String institution, String year, String intake) {
        Instant now = clock.instant();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        batches.values().stream()
                .filter(b -> b.institutionCode().equals(institution) && b.heading().applicationYear().equalsIgnoreCase(year)
                        && b.heading().intake().equalsIgnoreCase(intake))
                .sorted((x, y) -> x.receivedAt().compareTo(y.receivedAt()))
                .forEach(b -> b.items().forEach(i -> {
                    boolean ready = !now.isBefore(i.readyAt());
                    Candidate c = i.candidate();
                    Decision d = i.decision();
                    Status st = ready ? d.status() : Status.PROCESSING;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("user_id", c.indexNumber());
                    m.put("student_verification_id", st == Status.VERIFIED ? d.verificationNumber() : null);
                    m.put("first_name", c.firstName());
                    m.put("second_name", c.secondName());
                    m.put("last_name", c.surname());
                    m.put("gender", c.gender());
                    m.put("date_of_birth", isoDate(c.dateOfBirth()));
                    m.put("disability", c.impairment());
                    m.put("programme_id", b.heading().programmeId());
                    m.put("institution_id", institution);
                    m.put("verification_status", switch (st) {
                        case PROCESSING -> "Pending";
                        case VERIFIED -> "Verified and Approved";
                        case QUERIED -> "Returned for correction";
                        case NOT_VERIFIED -> "Not Approved";
                    });
                    m.put("multiple_selection", st == Status.VERIFIED && d.multipleAdmission() ? "Multiple selection" : "No multiple selection");
                    m.put("academic_year", b.heading().applicationYear());
                    m.put("intake", b.heading().intake().toUpperCase());
                    m.put("eligibility", st == Status.VERIFIED ? "Eligible" : st == Status.NOT_VERIFIED ? "Not Eligible" : "");
                    m.put("remarks", ready ? d.reason() : null);
                    rows.put(b.heading().programmeId() + "|" + nz(c.indexNumber()).toUpperCase(), m);
                }));
        return new ArrayList<>(rows.values());
    }

    /** d-MM-yyyy (as uploaded) to yyyy-MM-dd (as NACTVET's feedback writes it); anything else as it is. */
    static String isoDate(String dob) {
        if (dob == null) return null;
        String[] p = dob.trim().split("-");
        if (p.length == 3 && p[2].length() == 4) return p[2] + "-" + (p[1].length() == 1 ? "0" + p[1] : p[1]) + "-" + (p[0].length() == 1 ? "0" + p[0] : p[0]);
        return dob.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public synchronized ConfirmResult confirm(String institution, String indexNumber, String code) {
        Confirmation c = confirmations.get(indexNumber == null ? "" : indexNumber.trim().toUpperCase());
        if (c == null || !c.admittedBy.contains(institution)) return ConfirmResult.NOT_ADMITTED;
        if (c.confirmedBy != null && !c.confirmedBy.equals(institution)) return ConfirmResult.ALREADY_CONFIRMED_ELSEWHERE;
        if (code == null || !c.code.equals(code.trim())) return ConfirmResult.WRONG_CODE;
        c.confirmedBy = institution;
        save();
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
        save();
        return true;
    }

    public List<Batch> batches() {
        return batches.values().stream().sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt())).toList();
    }

    public synchronized void reset() {
        batches.clear();
        decided.clear();
        confirmations.clear();
        batchSeq.set(0);
        verificationSeq.set(0);
        outage = false;
        save();
    }

    public long processingSeconds() {
        return processingSeconds;
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
