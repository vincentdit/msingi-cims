package tz.co.msingi.cims.admission.selection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Allocates places to applicants by merit and preference, within programme capacity.
 *
 * Uses applicant-proposing deferred acceptance (Gale–Shapley): every applicant applies to their
 * highest remaining choice; each programme keeps its best-ranked applicants up to capacity and
 * releases the rest, who then try their next choice. The result is:
 * <ul>
 *   <li><b>merit-fair</b> — no applicant loses a place to a lower-ranked applicant in a programme they preferred;</li>
 *   <li><b>preference-respecting</b> — each applicant receives the highest choice their merit allows;</li>
 *   <li><b>deterministic</b> — ties are broken by submission time, then application id.</li>
 * </ul>
 * Pure and side-effect free.
 */
public final class SelectionAllocator {

    private SelectionAllocator() {
    }

    /** @param choices eligible window-programme ids in the applicant's order of preference */
    public record Candidate(Long applicationId, BigDecimal merit, OffsetDateTime submittedAt, List<Long> choices) {
    }

    /** @param programmeId null when the applicant could not be placed */
    public record Placement(Long applicationId, Long programmeId, Integer choiceOrder, Integer rankInProgramme) {
    }

    /** Higher merit first, then earlier submission, then lower application id. */
    public static final Comparator<Candidate> MERIT_ORDER = Comparator
            .comparing(Candidate::merit, Comparator.reverseOrder())
            .thenComparing(Candidate::submittedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::applicationId);

    public static List<Placement> allocate(List<Candidate> candidates, Map<Long, Integer> capacity) {
        Map<Long, PriorityQueue<Candidate>> held = new HashMap<>();   // worst-ranked at the head
        Map<Long, Integer> nextChoice = new HashMap<>();
        Deque<Candidate> free = new ArrayDeque<>(candidates.stream().sorted(MERIT_ORDER).toList());

        while (!free.isEmpty()) {
            Candidate c = free.poll();
            int i = nextChoice.getOrDefault(c.applicationId(), 0);
            if (i >= c.choices().size()) {
                continue; // no choices left: not placed
            }
            nextChoice.put(c.applicationId(), i + 1);
            Long programme = c.choices().get(i);
            int cap = capacity.getOrDefault(programme, 0);
            if (cap <= 0) {
                free.push(c);
                continue;
            }
            PriorityQueue<Candidate> q = held.computeIfAbsent(programme, k -> new PriorityQueue<>(MERIT_ORDER.reversed()));
            q.add(c);
            if (q.size() > cap) {
                free.push(q.poll()); // release the lowest-ranked; they try their next choice
            }
        }

        Map<Long, Placement> byApplication = new HashMap<>();
        held.forEach((programme, q) -> {
            List<Candidate> ranked = new ArrayList<>(q);
            ranked.sort(MERIT_ORDER);
            for (int r = 0; r < ranked.size(); r++) {
                Candidate c = ranked.get(r);
                byApplication.put(c.applicationId(), new Placement(c.applicationId(), programme, c.choices().indexOf(programme) + 1, r + 1));
            }
        });
        List<Placement> result = new ArrayList<>();
        for (Candidate c : candidates) {
            result.add(byApplication.getOrDefault(c.applicationId(), new Placement(c.applicationId(), null, null, null)));
        }
        return result;
    }
}
