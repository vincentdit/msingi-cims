package tz.co.msingi.cims.exams;

import tz.co.msingi.cims.exams.ModuleResult.Remark;
import tz.co.msingi.cims.exams.SemesterResult.Decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The examination rules as pure functions (FR-EXM-03/06/07), driven by a {@link RegulationSet}:
 * <ul>
 *   <li>each component counts score / out-of x weight; CA and SE totals keep two decimals; the module total is rounded to a whole
 *       mark (half up) and graded on the scale;</li>
 *   <li>a module passes when its grade is a passing grade and both the CA and SE totals reach their pass marks;</li>
 *   <li>a missed semester examination makes the module ABSENT (a special examination); any other missing mark makes it INCOMPLETE;
 *       a missed CA item counts as zero;</li>
 *   <li>GPA = sum(points x credits) / sum(credits) over graded modules, rounded or truncated as configured;</li>
 *   <li>decision: absent → special exam; incomplete → incomplete; no failures → pass; failed fewer than the supplementary limit
 *       (share of modules) → supplementary; otherwise discontinue below the GPA threshold, else repeat.</li>
 * </ul>
 */
public final class Grading {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Grading() {
    }

    public record Score(BigDecimal score, boolean absent) {
    }

    public record ModuleOutcome(BigDecimal caTotal, BigDecimal seTotal, BigDecimal total, String grade, BigDecimal points, Remark remark,
                                String failReason) {
        public boolean graded() {
            return remark == Remark.PASS || remark == Remark.FAIL;
        }
    }

    /** Grades one student's module from their component scores (keyed by component id; a missing key means no mark yet). */
    public static ModuleOutcome module(RegulationSet rs, List<AssessmentComponent> components, Map<Long, Score> scores) {
        BigDecimal ca = BigDecimal.ZERO, se = BigDecimal.ZERO;
        boolean missing = false, absentSe = false;
        for (AssessmentComponent c : components) {
            Score s = scores.get(c.getId());
            BigDecimal part;
            if (s == null || (!s.absent() && s.score() == null)) {
                missing = true;
                continue;
            }
            if (s.absent()) {
                if (c.getCategory() == AssessmentComponent.Category.SE) absentSe = true;
                part = BigDecimal.ZERO;
            } else {
                part = s.score().multiply(c.getWeight()).divide(c.getMaxScore(), 6, RoundingMode.HALF_UP);
            }
            if (c.getCategory() == AssessmentComponent.Category.CA) ca = ca.add(part);
            else se = se.add(part);
        }
        ca = ca.setScale(2, RoundingMode.HALF_UP);
        se = se.setScale(2, RoundingMode.HALF_UP);
        if (absentSe) return new ModuleOutcome(ca, se, null, null, null, Remark.ABSENT, "Absent from the semester examination");
        if (missing || components.isEmpty()) return new ModuleOutcome(ca, se, null, null, null, Remark.INCOMPLETE, "Marks missing");

        BigDecimal total = ca.add(se).setScale(0, RoundingMode.HALF_UP).min(HUNDRED);
        GradeBand band = band(rs, total);
        String reason = null;
        if (!band.isPassing()) {
            reason = "Grade " + band.getGrade() + " is not a pass";
        } else if (ca.compareTo(caPassMark(rs)) < 0) {
            reason = "Coursework " + plain(ca) + " is below the pass mark of " + plain(caPassMark(rs));
        } else if (se.compareTo(sePassMark(rs)) < 0) {
            reason = "Examination " + plain(se) + " is below the pass mark of " + plain(sePassMark(rs));
        }
        return new ModuleOutcome(ca, se, total, band.getGrade(), band.getPoints(), reason == null ? Remark.PASS : Remark.FAIL, reason);
    }

    public static BigDecimal caPassMark(RegulationSet rs) {
        return rs.getCaWeight().multiply(rs.getCaPassPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal sePassMark(RegulationSet rs) {
        return rs.getSeWeight().multiply(rs.getSePassPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static GradeBand band(RegulationSet rs, BigDecimal total) {
        int mark = total.setScale(0, RoundingMode.HALF_UP).intValue();
        return rs.getGradeBands().stream().filter(b -> mark >= b.getMinMark() && mark <= b.getMaxMark()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No grade for " + mark + " in " + rs.getName()));
    }

    /** GPA from the sum of points x credits and the graded credits; null when nothing was graded. */
    public static BigDecimal gpa(RegulationSet rs, BigDecimal pointsTotal, BigDecimal credits) {
        if (credits == null || credits.signum() == 0) return null;
        RoundingMode mode = rs.getGpaRounding() == RegulationSet.Rounding.TRUNCATE ? RoundingMode.DOWN : RoundingMode.HALF_UP;
        return pointsTotal.divide(credits, rs.getGpaDecimals(), mode);
    }

    /** The highest classification whose minimum the GPA reaches, if any. */
    public static String classify(RegulationSet rs, BigDecimal gpa) {
        if (gpa == null) return null;
        return rs.getClassifications().stream().filter(c -> gpa.compareTo(c.getMinGpa()) >= 0)
                .max(Comparator.comparing(ClassificationBand::getMinGpa)).map(ClassificationBand::getName).orElse(null);
    }

    /** The proposed progression decision for a semester (FR-EXM-07). */
    public static Decision decide(RegulationSet rs, Collection<ModuleOutcome> modules, BigDecimal gpa) {
        long absent = modules.stream().filter(m -> m.remark() == Remark.ABSENT).count();
        long incomplete = modules.stream().filter(m -> m.remark() == Remark.INCOMPLETE).count();
        long failed = modules.stream().filter(m -> m.remark() == Remark.FAIL).count();
        if (absent > 0) return Decision.SPECIAL_EXAM;
        if (incomplete > 0) return Decision.INCOMPLETE;
        if (failed == 0) return Decision.PASS;
        BigDecimal failedPercent = BigDecimal.valueOf(failed * 100).divide(BigDecimal.valueOf(modules.size()), 2, RoundingMode.HALF_UP);
        if (failedPercent.compareTo(rs.getSuppMaxFailedPercent()) < 0) return Decision.SUPPLEMENTARY;
        if (rs.getDiscontinueGpaBelow() != null && gpa != null && gpa.compareTo(rs.getDiscontinueGpaBelow()) < 0) return Decision.DISCONTINUE;
        return Decision.REPEAT;
    }

    static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
