package tz.co.msingi.cims.admission.selection;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.admission.selection.SelectionAllocator.Candidate;
import tz.co.msingi.cims.admission.selection.SelectionAllocator.Placement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionAllocatorTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2027-06-01T08:00:00Z");

    private static Candidate c(long id, double merit, Long... choices) {
        return new Candidate(id, BigDecimal.valueOf(merit), T0.plusMinutes(id), List.of(choices));
    }

    private static Map<Long, Placement> run(List<Candidate> cs, Map<Long, Integer> cap) {
        return SelectionAllocator.allocate(cs, cap).stream().collect(Collectors.toMap(Placement::applicationId, Function.identity()));
    }

    @Test
    void higherMeritGetsFirstChoiceAndOthersCascade() {
        // programme 10 has 1 place, programme 20 has 1 place
        Map<Long, Placement> r = run(List.of(c(1, 20, 10L, 20L), c(2, 30, 10L, 20L), c(3, 10, 10L, 20L)), Map.of(10L, 1, 20L, 1));
        assertThat(r.get(2L).programmeId()).isEqualTo(10L);   // best merit → first choice
        assertThat(r.get(1L).programmeId()).isEqualTo(20L);   // displaced to second choice
        assertThat(r.get(1L).choiceOrder()).isEqualTo(2);
        assertThat(r.get(3L).programmeId()).isNull();         // no place left
    }

    @Test
    void capacityIsNeverExceeded() {
        List<Candidate> cs = new ArrayList<>();
        for (long i = 1; i <= 50; i++) cs.add(c(i, i % 17, 10L, 20L, 30L));
        Map<Long, Placement> r = run(cs, Map.of(10L, 5, 20L, 3, 30L, 0));
        Map<Long, Long> counts = r.values().stream().filter(p -> p.programmeId() != null)
                .collect(Collectors.groupingBy(Placement::programmeId, Collectors.counting()));
        assertThat(counts.get(10L)).isEqualTo(5);
        assertThat(counts.get(20L)).isEqualTo(3);
        assertThat(counts).doesNotContainKey(30L);
    }

    @Test
    void tiesAreBrokenByEarlierSubmission() {
        Map<Long, Placement> r = run(List.of(c(5, 25, 10L), c(4, 25, 10L)), Map.of(10L, 1));
        assertThat(r.get(4L).programmeId()).isEqualTo(10L); // submitted earlier (id 4 → T0+4min)
        assertThat(r.get(5L).programmeId()).isNull();
    }

    @Test
    void applicantWithNoEligibleChoicesIsNotPlaced() {
        Map<Long, Placement> r = run(List.of(c(1, 35)), Map.of(10L, 10));
        assertThat(r.get(1L).programmeId()).isNull();
    }

    @Test
    void ranksAreContiguousWithinProgramme() {
        Map<Long, Placement> r = run(List.of(c(1, 10, 10L), c(2, 30, 10L), c(3, 20, 10L)), Map.of(10L, 3));
        assertThat(r.get(2L).rankInProgramme()).isEqualTo(1);
        assertThat(r.get(3L).rankInProgramme()).isEqualTo(2);
        assertThat(r.get(1L).rankInProgramme()).isEqualTo(3);
    }

    /** Fairness: no applicant prefers a programme that admitted someone with lower merit (no "justified envy"). */
    @RepeatedTest(25)
    void resultIsStable() {
        Random rnd = new Random();
        List<Long> programmes = List.of(10L, 20L, 30L, 40L);
        List<Candidate> cs = new ArrayList<>();
        for (long i = 1; i <= 120; i++) {
            List<Long> prefs = new ArrayList<>(programmes);
            Collections.shuffle(prefs, rnd);
            cs.add(new Candidate(i, BigDecimal.valueOf(rnd.nextInt(36)), T0.plusSeconds(rnd.nextInt(10_000)), prefs.subList(0, 1 + rnd.nextInt(3))));
        }
        Map<Long, Integer> cap = new HashMap<>(Map.of(10L, 15, 20L, 10, 30L, 20, 40L, 5));
        Map<Long, Placement> r = run(cs, cap);
        Map<Long, Candidate> byId = cs.stream().collect(Collectors.toMap(Candidate::applicationId, Function.identity()));

        for (Candidate a : cs) {
            Placement pa = r.get(a.applicationId());
            int got = pa.programmeId() == null ? Integer.MAX_VALUE : a.choices().indexOf(pa.programmeId());
            for (int k = 0; k < Math.min(got, a.choices().size()); k++) {
                Long preferred = a.choices().get(k);
                List<Candidate> admitted = r.values().stream().filter(p -> preferred.equals(p.programmeId()))
                        .map(p -> byId.get(p.applicationId())).toList();
                if (admitted.size() < cap.get(preferred)) {
                    throw new AssertionError("Applicant " + a.applicationId() + " left out of a programme with free places");
                }
                for (Candidate b : admitted) {
                    assertThat(SelectionAllocator.MERIT_ORDER.compare(b, a))
                            .as("applicant %s envies %s in programme %s", a.applicationId(), b.applicationId(), preferred)
                            .isLessThan(0);
                }
            }
        }
    }
}
