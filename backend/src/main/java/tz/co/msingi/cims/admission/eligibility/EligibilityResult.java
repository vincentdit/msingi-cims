package tz.co.msingi.cims.admission.eligibility;

import java.util.List;

/** Transparent eligibility outcome: which pathways and conditions passed, for applicants and officers. */
public record EligibilityResult(boolean eligible, String matchedPathway, String summary, List<PathwayResult> pathways) {

    public record PathwayResult(String label, boolean satisfied, List<ConditionResult> conditions) {
    }

    public record ConditionResult(String requirement, boolean satisfied, String found) {
    }
}
