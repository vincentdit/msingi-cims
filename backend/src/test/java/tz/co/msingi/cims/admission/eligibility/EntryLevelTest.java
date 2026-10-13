package tz.co.msingi.cims.admission.eligibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The NOAS progression rule: Form Four → NVA 1–3 and NTA 4; Form Six adds ACSEE-entry NTA 5/7; an NTA N holder → NTA N+1. */
class EntryLevelTest {

    static EligibilityEngine.Qualification q(String type, Integer level, boolean verified) {
        return new EligibilityEngine.Qualification(type, level, verified, List.of());
    }

    static final EntryRules CSEE_ONLY = new EntryRules("CSEE", List.of(new EntryRules.Pathway("CSEE",
            List.of(new EntryRules.Condition(EntryRules.ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null)))));
    static final EntryRules WITH_ACSEE = new EntryRules("ACSEE", List.of(new EntryRules.Pathway("ACSEE",
            List.of(new EntryRules.Condition(EntryRules.ConditionType.ACSEE_MIN_PRINCIPAL_PASSES, 1, null, null, null, null)))));

    @Test
    void formFourLeaversApplyForNvaAndNtaFour() {
        EntryLevel e = EntryLevel.of(List.of(q("CSEE", null, true)));
        assertThat(e.rule()).isEqualTo(EntryLevel.Rule.CSEE);
        assertThat(e.allows("NTA", 4, CSEE_ONLY)).isTrue();
        assertThat(e.allows("NVA", 2, CSEE_ONLY)).isTrue();
        assertThat(e.allows("NTA", 5, CSEE_ONLY)).isFalse();
        assertThat(e.allows("NTA", 6, CSEE_ONLY)).isFalse();
        assertThat(e.reason()).contains("Form Four (CSEE) results").contains("NVA Levels 1–3 and NTA Level 4");
    }

    @Test
    void formSixLeaversAlsoApplyForAcseeEntryNtaFiveAndSeven() {
        EntryLevel e = EntryLevel.of(List.of(q("CSEE", null, true), q("ACSEE", null, true)));
        assertThat(e.rule()).isEqualTo(EntryLevel.Rule.ACSEE);
        assertThat(e.allows("NTA", 4, CSEE_ONLY)).isTrue();
        assertThat(e.allows("NTA", 5, WITH_ACSEE)).isTrue();
        assertThat(e.allows("NTA", 5, CSEE_ONLY)).isFalse();
        assertThat(e.allows("NTA", 6, WITH_ACSEE)).isFalse();
        assertThat(e.allows("NTA", 7, WITH_ACSEE)).isTrue();
        assertThat(e.allows("NVA", 3, CSEE_ONLY)).isTrue();
        // unverified Form Six results do not count
        assertThat(EntryLevel.of(List.of(q("CSEE", null, true), q("ACSEE", null, false))).rule()).isEqualTo(EntryLevel.Rule.CSEE);
    }

    @Test
    void ntaHoldersApplyForTheNextLevelOnly() {
        EntryLevel e = EntryLevel.of(List.of(q("CSEE", null, true), q("NTA", 4, true), q("NTA", 5, true)));
        assertThat(e.rule()).isEqualTo(EntryLevel.Rule.NTA_NEXT_LEVEL);
        assertThat(e.ntaLevel()).isEqualTo(6);
        assertThat(e.allows("NTA", 6, CSEE_ONLY)).isTrue();
        assertThat(e.allows("NTA", 4, CSEE_ONLY)).isFalse();
        assertThat(e.allows("NTA", 7, CSEE_ONLY)).isFalse();
        assertThat(e.allows("NVA", 3, CSEE_ONLY)).isFalse();
        // an NTA award typed in counts only once the Admissions Office has verified it
        assertThat(EntryLevel.of(List.of(q("CSEE", null, true), q("NTA", 6, false))).rule()).isEqualTo(EntryLevel.Rule.CSEE);
        // NTA 8 is the top: no restriction
        assertThat(EntryLevel.of(List.of(q("NTA", 8, true))).allows("NTA", 4, CSEE_ONLY)).isTrue();
    }

    @Test
    void theEngineChecksTheEntryLevelBeforeTheRequirements() {
        EligibilityEngine engine = new EligibilityEngine();
        EligibilityResult r = engine.evaluate("NTA", 6, EntryRules.openEntry("Open"), List.of(q("CSEE", null, true)));
        assertThat(r.eligible()).isFalse();
        assertThat(r.summary()).startsWith("Not at your entry level");
        assertThat(engine.evaluate("NTA", 4, EntryRules.openEntry("Open"), List.of(q("CSEE", null, true))).eligible()).isTrue();
    }
}
