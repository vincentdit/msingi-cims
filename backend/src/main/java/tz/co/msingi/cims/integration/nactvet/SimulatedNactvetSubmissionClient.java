package tz.co.msingi.cims.integration.nactvet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development NACTVET for student registration and results, with the nactvet-mock rules: a student without a NACTVET
 * verification number is queried, as is a results row without one or with a module without a grade; everyone else is
 * registered (or the sheet accepted). Decided at once.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.submission", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNactvetSubmissionClient implements NactvetSubmissionClient {

    private final Map<String, Status> batches = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(100);

    @Override
    public String submitRegistrations(String institutionCode, String academicYear, List<RegStudent> students) {
        List<ItemStatus> items = new ArrayList<>();
        for (RegStudent s : students) {
            boolean ok = s.verificationNumber() != null && !s.verificationNumber().isBlank() && s.dateOfBirth() != null && s.programmeCode() != null;
            items.add(new ItemStatus(s.registrationNo(), ok ? "REGISTERED" : "QUERIED",
                    ok ? null : s.verificationNumber() == null || s.verificationNumber().isBlank() ? "No NACTVET admission verification number"
                            : "Programme code or date of birth missing",
                    ok ? "NV" + academicYear.substring(2, 4) + String.format("%06d", seq.incrementAndGet()) : null));
        }
        String ref = "SIM-REG-" + seq.incrementAndGet();
        batches.put(ref, new Status("COMPLETED", items));
        log.info("[NACTVET simulated] registration batch {} for {}: {} student(s)", ref, institutionCode, students.size());
        return ref;
    }

    @Override
    public Status registrationStatus(String batchRef) {
        Status s = batches.get(batchRef);
        if (s == null) throw new NactvetUnavailableException("NACTVET has no registration batch " + batchRef, null);
        return s;
    }

    @Override
    public String submitResults(String institutionCode, Results results) {
        List<ItemStatus> items = new ArrayList<>();
        for (ResultRow r : results.rows()) {
            String why = r.verificationNumber() == null || r.verificationNumber().isBlank() ? "No NACTVET verification number"
                    : r.modules().stream().anyMatch(m -> m.grade() == null || m.grade().isBlank()) ? "A module has no grade" : null;
            items.add(new ItemStatus(r.registrationNo(), why == null ? "ACCEPTED" : "QUERIED", why, null));
        }
        String ref = "SIM-RES-" + seq.incrementAndGet();
        batches.put(ref, new Status(items.stream().anyMatch(i -> "QUERIED".equals(i.status())) ? "QUERIED" : "ACCEPTED", items));
        return ref;
    }

    @Override
    public Status resultStatus(String batchRef) {
        return registrationStatus(batchRef);
    }
}
