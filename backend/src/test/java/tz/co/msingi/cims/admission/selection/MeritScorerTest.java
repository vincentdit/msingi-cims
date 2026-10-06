package tz.co.msingi.cims.admission.selection;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine.Qualification;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine.Qualification.Subject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeritScorerTest {

    private final MeritScorer scorer = new MeritScorer();

    @Test
    void bestSevenCseeSubjectsCount() {
        Qualification csee = new Qualification("CSEE", null, true, List.of(
                new Subject("1", "CIVICS", "A"), new Subject("2", "HISTORY", "A"), new Subject("3", "GEOGRAPHY", "B"),
                new Subject("4", "KISWAHILI", "B"), new Subject("5", "ENGLISH", "C"), new Subject("6", "PHYSICS", "C"),
                new Subject("7", "CHEMISTRY", "D"), new Subject("8", "BIOLOGY", "F"), new Subject("9", "MATHS", "D")));
        // best 7: 5+5+4+4+3+3+2 = 26
        assertThat(scorer.score(List.of(csee))).isEqualByComparingTo("26.00");
    }

    @Test
    void resitImprovesGradeAndPriorAwardAddsPoints() {
        Qualification first = new Qualification("CSEE", null, true, List.of(new Subject("1", "MATHS", "F")));
        Qualification resit = new Qualification("CSEE", null, true, List.of(new Subject("1", "MATHS", "C")));
        Qualification nva = new Qualification("NVA", 3, true, List.of());
        assertThat(scorer.score(List.of(first, resit, nva))).isEqualByComparingTo("18.00"); // 3 + 5×3
    }

    @Test
    void unverifiedQualificationsDoNotCount() {
        Qualification nva = new Qualification("NVA", 3, false, List.of());
        assertThat(scorer.score(List.of(nva))).isEqualByComparingTo("0.00");
    }
}
