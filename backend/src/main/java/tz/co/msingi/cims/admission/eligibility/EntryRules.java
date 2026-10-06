package tz.co.msingi.cims.admission.eligibility;

import java.util.List;

/**
 * NACTVET minimum entry requirements for one programme, stored as JSON per admission window.
 * An applicant is eligible if ANY pathway is satisfied; a pathway is satisfied if ALL its conditions are.
 *
 * Example — Basic Technician Certificate (NTA 4):
 * <pre>
 * { "summary": "Four passes in CSEE including Basic Mathematics, or NVA Level III",
 *   "pathways": [
 *     { "label": "CSEE route", "conditions": [
 *         { "type": "CSEE_MIN_PASSES", "count": 4, "minGrade": "D" },
 *         { "type": "CSEE_SUBJECT", "subjects": ["BASIC MATHEMATICS"], "minGrade": "D" } ] },
 *     { "label": "NVA Level III holders", "conditions": [
 *         { "type": "PRIOR_AWARD", "family": "NVA", "minLevel": 3 } ] } ] }
 * </pre>
 */
public record EntryRules(String summary, List<Pathway> pathways) {

    public EntryRules {
        pathways = pathways == null ? List.of() : List.copyOf(pathways);
    }

    public record Pathway(String label, List<Condition> conditions) {
        public Pathway {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /**
     * @param type     condition type
     * @param count    number of passes / subjects required (default 1)
     * @param minGrade minimum grade (CSEE default D; ACSEE default E)
     * @param subjects subject names or NECTA codes, any of which may satisfy the condition
     * @param family   NTA or NVA (PRIOR_AWARD)
     * @param minLevel minimum award level (PRIOR_AWARD)
     */
    public record Condition(ConditionType type, Integer count, String minGrade, List<String> subjects,
                            String family, Integer minLevel) {
    }

    public enum ConditionType {
        /** At least {count} CSEE passes at {minGrade} or better. */
        CSEE_MIN_PASSES,
        /** At least {count} of the listed CSEE subjects at {minGrade} or better. */
        CSEE_SUBJECT,
        /** At least {count} ACSEE principal passes (A–E). */
        ACSEE_MIN_PRINCIPAL_PASSES,
        /** At least {count} of the listed ACSEE subjects at {minGrade} or better. */
        ACSEE_SUBJECT,
        /** Holder of an NTA/NVA award at {minLevel} or above. */
        PRIOR_AWARD
    }

    public static EntryRules openEntry(String summary) {
        return new EntryRules(summary, List.of());
    }
}
