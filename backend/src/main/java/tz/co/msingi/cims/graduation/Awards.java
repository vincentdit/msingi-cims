package tz.co.msingi.cims.graduation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.exams.ExamRepositories.SemesterResultRepository;
import tz.co.msingi.cims.exams.ResultSheet;
import tz.co.msingi.cims.exams.SemesterResult;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a student has completed their programme and qualifies for the award (BP-15 trigger: final results confirmed by the
 * Governing Board). Taken from published results, a semester's sheet after resits where there is one: the latest published semester
 * must be the programme's last (by year of study and semester number, two semesters a year), with a Pass, and no earlier semester may
 * still be outstanding (supplementary, special examination, carry over, incomplete).
 */
@Component
@RequiredArgsConstructor
public class Awards {

    private static final Set<SemesterResult.Decision> OUTSTANDING = EnumSet.of(SemesterResult.Decision.SUPPLEMENTARY,
            SemesterResult.Decision.SPECIAL_EXAM, SemesterResult.Decision.INCOMPLETE, SemesterResult.Decision.CARRY_OVER,
            SemesterResult.Decision.REPEAT, SemesterResult.Decision.DISCONTINUE);

    private final SemesterResultRepository results;

    public record Outcome(boolean finalist, boolean qualified, String reason, String awardName, String classification, BigDecimal cgpa,
                          BigDecimal creditsEarned, String completedSemester, int semestersCompleted, int semestersRequired) {
    }

    public Outcome of(Student s) {
        Map<Long, SemesterResult> bySemester = new HashMap<>();
        for (SemesterResult r : results.findByStudentIdOrderByIdAsc(s.getId())) {
            if (r.getSheet().getStatus() != ResultSheet.Status.PUBLISHED) continue;
            Long sem = r.getSheet().getSemester().getId();
            SemesterResult have = bySemester.get(sem);
            if (have == null || r.getSheet().getRound() == ResultSheet.Round.RESIT) bySemester.put(sem, r);
        }
        int required = Math.max(1, s.getProgramme().getDurationSemesters());
        String award = s.getProgramme().getAwardName() == null ? s.getProgramme().getName() : s.getProgramme().getAwardName();
        if (bySemester.isEmpty()) return new Outcome(false, false, "No published results yet", award, null, null, BigDecimal.ZERO, null, 0, required);
        List<SemesterResult> ordered = bySemester.values().stream()
                .sorted(Comparator.comparing((SemesterResult r) -> r.getSheet().getSemester().getStartDate())).toList();
        SemesterResult last = ordered.get(ordered.size() - 1);
        int position = (last.getSheet().getYearOfStudy() - 1) * 2 + last.getSheet().getSemester().getSemesterNo();
        BigDecimal credits = ordered.stream().map(SemesterResult::getCreditsPassed).reduce(BigDecimal.ZERO, BigDecimal::add);
        String completed = last.getSheet().getSemester().getName() + " " + last.getSheet().getSemester().getAcademicYear().getCode();
        BigDecimal cgpa = last.getCgpa() == null ? null : last.getCgpa().setScale(last.getSheet().getRegulationSet().getGpaDecimals(), RoundingMode.HALF_UP);
        boolean finalist = position >= required;
        String reason = null;
        if (!finalist) {
            reason = "Results published up to year " + last.getSheet().getYearOfStudy() + " semester " + last.getSheet().getSemester().getSemesterNo()
                    + " of " + required + " semesters";
        } else if (last.getDecision() != SemesterResult.Decision.PASS) {
            reason = "Final semester decision: " + label(last.getDecision());
        } else {
            SemesterResult open = ordered.stream().filter(r -> OUTSTANDING.contains(r.getDecision())).findFirst().orElse(null);
            if (open != null) {
                reason = open.getSheet().getSemester().getName() + " " + open.getSheet().getSemester().getAcademicYear().getCode() + " still outstanding ("
                        + label(open.getDecision()) + ")";
            }
        }
        return new Outcome(finalist, finalist && reason == null, reason, award, last.getClassification(), cgpa, credits, completed, position, required);
    }

    private static String label(SemesterResult.Decision d) {
        String n = d.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
