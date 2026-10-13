package tz.co.msingi.cims.admission.eligibility;

import java.util.List;
import java.util.Objects;

/**
 * The level an applicant may apply at, from their highest verified qualification — the progression rule NOAS applies:
 * <ul>
 *   <li>an NTA award at level N: only NTA level N+1 (no restriction at NTA 8, the top);</li>
 *   <li>otherwise Form Six (ACSEE): NVA 1–3, NTA 4, and NTA 5 or NTA 7 programmes that take ACSEE entrants;</li>
 *   <li>otherwise Form Four (CSEE): NVA 1–3 and NTA 4.</li>
 * </ul>
 * Only verified qualifications count: NTA awards from NACTVET (or checked by the Admissions Office) and NECTA results.
 */
public record EntryLevel(Rule rule, Integer ntaLevel, String label, String basis) {

    public enum Rule { NTA_NEXT_LEVEL, ACSEE, CSEE, ANY }

    public static EntryLevel of(List<EligibilityEngine.Qualification> qualifications) {
        List<EligibilityEngine.Qualification> verified = qualifications.stream().filter(EligibilityEngine.Qualification::verified).toList();
        Integer highestNta = verified.stream().filter(q -> "NTA".equals(q.type())).map(EligibilityEngine.Qualification::awardLevel)
                .filter(Objects::nonNull).max(Integer::compare).orElse(null);
        if (highestNta != null) {
            String basis = "NTA Level " + highestNta + " award";
            if (highestNta >= 8) return new EntryLevel(Rule.ANY, null, "any level", basis);
            int next = highestNta + 1;
            return new EntryLevel(Rule.NTA_NEXT_LEVEL, next, "NTA Level " + next, basis);
        }
        if (verified.stream().anyMatch(q -> "ACSEE".equals(q.type()))) {
            return new EntryLevel(Rule.ACSEE, null, "NVA Levels 1–3, NTA Level 4, and NTA Level 5 or 7 programmes that admit Form Six leavers",
                    "Form Six (ACSEE) results");
        }
        return new EntryLevel(Rule.CSEE, null, "NVA Levels 1–3 and NTA Level 4", "Form Four (CSEE) results");
    }

    /** Whether a programme of this award family ("NTA"/"NVA") and level, with these entry rules, is at this entry level. */
    public boolean allows(String family, int level, EntryRules rules) {
        boolean nva = "NVA".equalsIgnoreCase(family);
        return switch (rule) {
            case ANY -> true;
            case NTA_NEXT_LEVEL -> !nva && level == ntaLevel;
            case CSEE -> nva ? level <= 3 : level == 4;
            case ACSEE -> nva ? level <= 3 : level == 4 || ((level == 5 || level == 7) && takesAcsee(rules));
        };
    }

    /** The programme's entry requirements have a Form Six (ACSEE) route. */
    static boolean takesAcsee(EntryRules rules) {
        return rules != null && rules.pathways().stream().flatMap(p -> p.conditions().stream())
                .anyMatch(c -> c.type() == EntryRules.ConditionType.ACSEE_MIN_PRINCIPAL_PASSES || c.type() == EntryRules.ConditionType.ACSEE_SUBJECT);
    }

    public String reason() {
        return "Not at your entry level: with your " + basis + " you can apply for " + label + " programmes";
    }
}
