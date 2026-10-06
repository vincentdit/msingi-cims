package tz.co.msingi.cims.admission;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine.Qualification;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine.Qualification.Subject;
import tz.co.msingi.cims.admission.eligibility.EligibilityResult;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Condition;
import tz.co.msingi.cims.admission.eligibility.EntryRules.ConditionType;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Pathway;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineTest {

    private final EligibilityEngine engine = new EligibilityEngine();

    private static final EntryRules BTC_IT = new EntryRules("4 passes incl. Basic Mathematics, or NVA III", List.of(
            new Pathway("CSEE", List.of(
                    new Condition(ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                    new Condition(ConditionType.CSEE_SUBJECT, 1, "D", List.of("BASIC MATHEMATICS"), null, null))),
            new Pathway("NVA III", List.of(new Condition(ConditionType.PRIOR_AWARD, null, null, null, "NVA", 3)))));

    private static Qualification csee(String... nameGrade) {
        List<Subject> subjects = new java.util.ArrayList<>();
        for (int i = 0; i < nameGrade.length; i += 2) {
            subjects.add(new Subject("0" + i, nameGrade[i], nameGrade[i + 1]));
        }
        return new Qualification("CSEE", null, true, subjects);
    }

    @Test
    void eligibleWithFourPassesIncludingMaths() {
        EligibilityResult r = engine.evaluate(BTC_IT, List.of(csee(
                "CIVICS", "C", "KISWAHILI", "D", "ENGLISH LANGUAGE", "D", "BASIC MATHEMATICS", "D", "PHYSICS", "F")));
        assertThat(r.eligible()).isTrue();
        assertThat(r.matchedPathway()).isEqualTo("CSEE");
    }

    @Test
    void notEligibleWithoutMathsEvenWithManyPasses() {
        EligibilityResult r = engine.evaluate(BTC_IT, List.of(csee(
                "CIVICS", "A", "HISTORY", "B", "KISWAHILI", "B", "ENGLISH LANGUAGE", "C", "BASIC MATHEMATICS", "F")));
        assertThat(r.eligible()).isFalse();
        assertThat(r.pathways().get(0).conditions().get(1).satisfied()).isFalse();
    }

    @Test
    void codesAndNamesAreNotDoubleCounted() {
        // three passes only — must not count each subject twice (name + code)
        EligibilityResult r = engine.evaluate(BTC_IT, List.of(csee(
                "CIVICS", "C", "KISWAHILI", "D", "BASIC MATHEMATICS", "D", "PHYSICS", "F")));
        assertThat(r.eligible()).isFalse();
        assertThat(r.pathways().get(0).conditions().get(0).found()).isEqualTo("3 pass(es)");
    }

    @Test
    void bestGradeAcrossTwoSittingsIsUsed() {
        Qualification first = csee("CIVICS", "D", "KISWAHILI", "D", "ENGLISH LANGUAGE", "D", "BASIC MATHEMATICS", "F");
        Qualification resit = csee("BASIC MATHEMATICS", "C");
        assertThat(engine.evaluate(BTC_IT, List.of(first, resit)).eligible()).isTrue();
    }

    @Test
    void verifiedPriorAwardOpensAlternativePathway() {
        Qualification nva = new Qualification("NVA", 3, true, List.of());
        EligibilityResult r = engine.evaluate(BTC_IT, List.of(nva));
        assertThat(r.eligible()).isTrue();
        assertThat(r.matchedPathway()).isEqualTo("NVA III");
    }

    @Test
    void unverifiedQualificationsAreIgnored() {
        Qualification nva = new Qualification("NVA", 3, false, List.of());
        assertThat(engine.evaluate(BTC_IT, List.of(nva)).eligible()).isFalse();
    }

    @Test
    void openEntryProgrammeAcceptsEveryone() {
        assertThat(engine.evaluate(EntryRules.openEntry("Standard Seven"), List.of()).eligible()).isTrue();
    }

    @Test
    void acseePrincipalPasses() {
        EntryRules rules = new EntryRules("1 principal pass", List.of(new Pathway("ACSEE", List.of(
                new Condition(ConditionType.ACSEE_MIN_PRINCIPAL_PASSES, 1, null, null, null, null)))));
        Qualification acsee = new Qualification("ACSEE", null, true, List.of(
                new Subject("131", "PHYSICS", "E"), new Subject("132", "CHEMISTRY", "S")));
        assertThat(engine.evaluate(rules, List.of(acsee)).eligible()).isTrue();
    }
}
