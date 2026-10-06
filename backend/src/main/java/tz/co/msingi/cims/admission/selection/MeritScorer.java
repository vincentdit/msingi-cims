package tz.co.msingi.cims.admission.selection;

import org.springframework.stereotype.Component;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merit points used to rank eligible applicants (BP-03).
 *
 * <pre>
 * merit = best 7 CSEE subjects   (A=5, B+=4.5, B=4, C=3, D=2, E=1, F=0)        → max 35
 *       + best 3 ACSEE subjects  (A=5, B=4, C=3, D=2, E=1, S=0.5, F=0)        → max 15
 *       + 5 × highest verified prior award level (NVA I–III, NTA 4–8)
 * </pre>
 * Only verified qualifications count; for repeated sittings the best grade per subject is used.
 * Ties are broken by submission time, then application id (see {@link SelectionAllocator}).
 */
@Component
public class MeritScorer {

    public static final String FORMULA =
            "Best 7 CSEE subjects (A=5 … F=0) + best 3 ACSEE subjects (A=5 … E=1, S=0.5) + 5 × highest prior NVA/NTA award level";

    private static final Map<String, Double> CSEE = Map.of("A", 5.0, "B+", 4.5, "B", 4.0, "C", 3.0, "D", 2.0, "E", 1.0, "F", 0.0);
    private static final Map<String, Double> ACSEE = Map.of("A", 5.0, "B", 4.0, "C", 3.0, "D", 2.0, "E", 1.0, "S", 0.5, "F", 0.0);

    public BigDecimal score(List<EligibilityEngine.Qualification> qualifications) {
        List<EligibilityEngine.Qualification> verified = qualifications.stream().filter(EligibilityEngine.Qualification::verified).toList();
        double total = best(verified, "CSEE", CSEE, 7) + best(verified, "ACSEE", ACSEE, 3);
        int award = verified.stream()
                .filter(q -> ("NTA".equals(q.type()) || "NVA".equals(q.type())) && q.awardLevel() != null)
                .mapToInt(EligibilityEngine.Qualification::awardLevel).max().orElse(0);
        total += 5.0 * award;
        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    private static double best(List<EligibilityEngine.Qualification> quals, String type, Map<String, Double> scale, int take) {
        Map<String, Double> bestBySubject = new HashMap<>();
        quals.stream().filter(q -> type.equals(q.type())).flatMap(q -> q.subjects().stream()).forEach(s -> {
            Double points = scale.get(s.grade() == null ? "" : s.grade().trim().toUpperCase(Locale.ROOT));
            if (points == null) return;
            String key = (s.name() == null || s.name().isBlank() ? s.code() : s.name()).trim().toUpperCase(Locale.ROOT);
            bestBySubject.merge(key, points, Math::max);
        });
        return bestBySubject.values().stream().sorted(Comparator.reverseOrder()).limit(take).mapToDouble(Double::doubleValue).sum();
    }
}
