package tz.co.msingi.cims.admission.eligibility;

import org.springframework.stereotype.Component;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Condition;
import tz.co.msingi.cims.admission.eligibility.EntryRules.ConditionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates NACTVET minimum entry requirements (BR-03, BRU-02) against an applicant's verified
 * qualifications. Pure and side-effect free, so it is easy to unit test and to explain.
 *
 * Where an applicant sat an examination more than once, the best grade per subject is used.
 */
@Component
public class EligibilityEngine {

    /** Higher is better. CSEE: A, B+ (2014–2015), B, C, D, E (pre-2014), F. */
    private static final Map<String, Integer> CSEE_RANK = Map.of(
            "A", 7, "B+", 6, "B", 5, "C", 4, "D", 3, "E", 2, "F", 1);
    /** ACSEE: A–E principal passes, S subsidiary, F fail. */
    private static final Map<String, Integer> ACSEE_RANK = Map.of(
            "A", 7, "B", 6, "C", 5, "D", 4, "E", 3, "S", 2, "F", 1);

    /** A verified qualification as seen by the engine. */
    public record Qualification(String type, Integer awardLevel, boolean verified, List<Subject> subjects) {
        public record Subject(String code, String name, String grade) {
        }
    }

    /**
     * Eligibility for a programme: the applicant's entry level first (as in NOAS, only the level their highest
     * qualification leads to), then the programme's minimum entry requirements.
     */
    public EligibilityResult evaluate(String awardFamily, int awardLevel, EntryRules rules, List<Qualification> qualifications) {
        EntryLevel level = EntryLevel.of(qualifications);
        if (!level.allows(awardFamily, awardLevel, rules)) {
            return new EligibilityResult(false, null, level.reason(), List.of());
        }
        return evaluate(rules, qualifications);
    }

    public EligibilityResult evaluate(EntryRules rules, List<Qualification> qualifications) {
        List<Qualification> verified = qualifications.stream().filter(Qualification::verified).toList();
        if (rules.pathways().isEmpty()) {
            return new EligibilityResult(true, "Open entry", rules.summary(), List.of());
        }
        Grades bestCsee = bestGrades(verified, "CSEE", CSEE_RANK);
        Grades bestAcsee = bestGrades(verified, "ACSEE", ACSEE_RANK);

        List<EligibilityResult.PathwayResult> results = new ArrayList<>();
        String matched = null;
        for (EntryRules.Pathway p : rules.pathways()) {
            List<EligibilityResult.ConditionResult> conditions = new ArrayList<>();
            boolean all = true;
            for (Condition c : p.conditions()) {
                EligibilityResult.ConditionResult r = check(c, bestCsee, bestAcsee, verified);
                conditions.add(r);
                all &= r.satisfied();
            }
            results.add(new EligibilityResult.PathwayResult(p.label(), all, conditions));
            if (all && matched == null) {
                matched = p.label();
            }
        }
        return new EligibilityResult(matched != null, matched, rules.summary(), results);
    }

    private EligibilityResult.ConditionResult check(Condition c, Grades csee, Grades acsee,
                                                    List<Qualification> quals) {
        int count = c.count() == null || c.count() < 1 ? 1 : c.count();
        return switch (c.type()) {
            case CSEE_MIN_PASSES -> {
                String min = grade(c.minGrade(), "D");
                long passes = csee.values().stream().filter(g -> atLeast(g, min, CSEE_RANK)).count();
                yield new EligibilityResult.ConditionResult(
                        "At least " + count + " CSEE passes at grade " + min + " or better",
                        passes >= count, csee.isEmpty() ? "No verified CSEE results" : passes + " pass(es)");
            }
            case CSEE_SUBJECT -> subject(c, count, csee, CSEE_RANK, "CSEE", "D");
            case ACSEE_SUBJECT -> subject(c, count, acsee, ACSEE_RANK, "ACSEE", "E");
            case ACSEE_MIN_PRINCIPAL_PASSES -> {
                long principal = acsee.values().stream().filter(g -> atLeast(g, "E", ACSEE_RANK)).count();
                yield new EligibilityResult.ConditionResult(
                        "At least " + count + " ACSEE principal pass(es)",
                        principal >= count, acsee.isEmpty() ? "No verified ACSEE results" : principal + " principal pass(es)");
            }
            case PRIOR_AWARD -> {
                String family = c.family() == null ? "NTA" : c.family().toUpperCase(Locale.ROOT);
                int minLevel = c.minLevel() == null ? 1 : c.minLevel();
                int best = quals.stream().filter(q -> family.equals(q.type()) && q.awardLevel() != null)
                        .mapToInt(Qualification::awardLevel).max().orElse(0);
                yield new EligibilityResult.ConditionResult(
                        family + " award at level " + minLevel + " or above",
                        best >= minLevel, best == 0 ? "No verified " + family + " award" : family + " level " + best);
            }
        };
    }

    private EligibilityResult.ConditionResult subject(Condition c, int count, Grades best,
                                                      Map<String, Integer> rank, String exam, String defaultMin) {
        String min = grade(c.minGrade(), defaultMin);
        List<String> wanted = c.subjects() == null ? List.of() : c.subjects();
        List<String> met = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String w : wanted) {
            String g = best.get(w);
            if (g != null) {
                seen.add(w + " " + g);
                if (atLeast(g, min, rank)) {
                    met.add(w);
                }
            }
        }
        String requirement = (count == 1 && wanted.size() == 1 ? wanted.get(0)
                : count + " of " + String.join(" / ", wanted)) + " in " + exam + " at grade " + min + " or better";
        return new EligibilityResult.ConditionResult(requirement, met.size() >= count,
                seen.isEmpty() ? "Subject(s) not found" : String.join(", ", seen));
    }

    /** Best grade per subject (keyed by subject name), with a code → name index for look-ups by NECTA code. */
    record Grades(Map<String, String> byName, Map<String, String> codeToName) {
        boolean isEmpty() {
            return byName.isEmpty();
        }

        java.util.Collection<String> values() {
            return byName.values();
        }

        String get(String nameOrCode) {
            String key = normalise(nameOrCode);
            String g = byName.get(key);
            if (g == null && codeToName.containsKey(key)) {
                g = byName.get(codeToName.get(key));
            }
            return g;
        }
    }

    private static Grades bestGrades(List<Qualification> quals, String type, Map<String, Integer> rank) {
        Map<String, String> best = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        quals.stream().filter(q -> type.equals(q.type())).flatMap(q -> q.subjects().stream()).forEach(s -> {
            String g = s.grade() == null ? "" : s.grade().trim().toUpperCase(Locale.ROOT);
            if (!rank.containsKey(g)) {
                return; // absent, withheld, etc.
            }
            String name = normalise(s.name()).isEmpty() ? normalise(s.code()) : normalise(s.name());
            best.merge(name, g, (a, b) -> rank.get(a) >= rank.get(b) ? a : b);
            if (!normalise(s.code()).isEmpty()) {
                codes.put(normalise(s.code()), name);
            }
        });
        return new Grades(best, codes);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String grade(String g, String dflt) {
        return g == null || g.isBlank() ? dflt : g.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean atLeast(String grade, String min, Map<String, Integer> rank) {
        Integer g = rank.get(grade);
        Integer m = rank.get(min);
        return g != null && m != null && g >= m;
    }

    /** Exposed for tests: condition type list (keeps enum referenced for documentation tools). */
    static ConditionType[] supportedConditions() {
        return ConditionType.values();
    }
}
