package tz.co.msingi.cims.exams;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.exams.ExamDtos.GradeBandDto;
import tz.co.msingi.cims.exams.ExamDtos.RegulationRequest;
import tz.co.msingi.cims.exams.Grading.ModuleOutcome;
import tz.co.msingi.cims.exams.Grading.Score;
import tz.co.msingi.cims.exams.ModuleResult.Remark;
import tz.co.msingi.cims.exams.SemesterResult.Decision;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The examination rules of {@link Grading} on the default NTA 4-5 and NTA 6 regulations. */
class GradingTest {

    static RegulationSet regs(RegulationRequest r) {
        RegulationSet rs = new RegulationSet();
        rs.setName(r.name());
        rs.setCaWeight(r.caWeight());
        rs.setSeWeight(r.seWeight());
        rs.setCaPassPercent(r.caPassPercent());
        rs.setSePassPercent(r.sePassPercent());
        rs.setGpaDecimals(r.gpaDecimals());
        rs.setGpaRounding(r.gpaRounding());
        rs.setSuppMaxFailedPercent(r.suppMaxFailedPercent());
        rs.setDiscontinueGpaBelow(r.discontinueGpaBelow());
        for (GradeBandDto b : r.gradeBands()) {
            GradeBand g = new GradeBand();
            g.setGrade(b.grade());
            g.setMinMark(b.minMark());
            g.setMaxMark(b.maxMark());
            g.setPoints(b.points());
            g.setPassing(b.passing());
            rs.getGradeBands().add(g);
        }
        r.classifications().forEach(c -> {
            ClassificationBand k = new ClassificationBand();
            k.setName(c.name());
            k.setMinGpa(c.minGpa());
            k.setMaxGpa(c.maxGpa());
            rs.getClassifications().add(k);
        });
        return rs;
    }

    static final RegulationSet CERT = regs(RegulationDefaults.ntaCertificate(1L));
    static final RegulationSet DIPLOMA = regs(RegulationDefaults.ntaDiploma(1L));

    static List<AssessmentComponent> plan(Object... parts) throws Exception {
        List<AssessmentComponent> out = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < parts.length; i += 3) {
            AssessmentComponent c = new AssessmentComponent();
            Field f = c.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id++);
            c.setName("c" + i);
            c.setCategory(AssessmentComponent.Category.valueOf((String) parts[i]));
            c.setWeight(new BigDecimal(parts[i + 1].toString()));
            c.setMaxScore(new BigDecimal(parts[i + 2].toString()));
            out.add(c);
        }
        return out;
    }

    static Map<Long, Score> marks(String... values) {
        Map<Long, Score> m = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) continue;
            m.put((long) i + 1, values[i].equals("ABS") ? new Score(null, true) : new Score(new BigDecimal(values[i]), false));
        }
        return m;
    }

    @Test
    void componentsAreScaledToTheirWeightAndTheTotalIsRoundedToAWholeMark() throws Exception {
        List<AssessmentComponent> p = plan("CA", 15, 30, "CA", 25, 50, "SE", 60, 100);
        ModuleOutcome o = Grading.module(CERT, p, marks("27", "45", "79.5"));
        assertThat(o.caTotal()).isEqualByComparingTo("36");
        assertThat(o.seTotal()).isEqualByComparingTo("47.70");
        assertThat(o.total()).isEqualByComparingTo("84");
        assertThat(o.grade()).isEqualTo("A");
        assertThat(o.points()).isEqualByComparingTo("4");
        assertThat(o.remark()).isEqualTo(Remark.PASS);
        // 64.5 rounds up to 65: a B, not a C
        assertThat(Grading.module(CERT, plan("CA", 40, 100, "SE", 60, 100), marks("60", "67.5")).grade()).isEqualTo("B");
    }

    @Test
    void aDIsNotAPassAndBothComponentsMustReachTheirPassMarks() throws Exception {
        List<AssessmentComponent> p = plan("CA", 40, 100, "SE", 60, 100);
        ModuleOutcome d = Grading.module(CERT, p, marks("60", "40"));        // 24 + 24 = 48
        assertThat(d.grade()).isEqualTo("D");
        assertThat(d.remark()).isEqualTo(Remark.FAIL);
        assertThat(d.failReason()).isEqualTo("Grade D is not a pass");
        ModuleOutcome lowCa = Grading.module(CERT, p, marks("30", "80"));    // 12 + 48 = 60, a C, but CA 12 < 20
        assertThat(lowCa.grade()).isEqualTo("C");
        assertThat(lowCa.remark()).isEqualTo(Remark.FAIL);
        assertThat(lowCa.failReason()).isEqualTo("Coursework 12 is below the pass mark of 20");
        ModuleOutcome lowSe = Grading.module(CERT, p, marks("100", "45"));   // 40 + 27 = 67, SE 27 < 30
        assertThat(lowSe.remark()).isEqualTo(Remark.FAIL);
        assertThat(lowSe.failReason()).startsWith("Examination 27");
    }

    @Test
    void aMissedExaminationIsAbsentAndAMissingMarkIncomplete() throws Exception {
        List<AssessmentComponent> p = plan("CA", 40, 100, "SE", 60, 100);
        assertThat(Grading.module(CERT, p, marks("70", "ABS")).remark()).isEqualTo(Remark.ABSENT);
        assertThat(Grading.module(CERT, p, marks("70", null)).remark()).isEqualTo(Remark.INCOMPLETE);
        ModuleOutcome missedTest = Grading.module(CERT, plan("CA", 20, 100, "CA", 20, 100, "SE", 60, 100), marks("ABS", "100", "100"));
        assertThat(missedTest.caTotal()).isEqualByComparingTo("20");
        assertThat(missedTest.remark()).isEqualTo(Remark.PASS);
    }

    @Test
    void theDiplomaScaleHasBPlusAndFivePoints() throws Exception {
        List<AssessmentComponent> p = plan("CA", 40, 100, "SE", 60, 100);
        ModuleOutcome o = Grading.module(DIPLOMA, p, marks("75", "70"));     // 30 + 42 = 72
        assertThat(o.grade()).isEqualTo("B+");
        assertThat(o.points()).isEqualByComparingTo("4");
        assertThat(Grading.module(DIPLOMA, p, marks("80", "75")).grade()).isEqualTo("A"); // 32 + 45 = 77
    }

    @Test
    void gpaIsCreditWeightedAndRoundedOrTruncated() {
        assertThat(Grading.gpa(CERT, new BigDecimal("110"), new BigDecimal("30"))).isEqualByComparingTo("3.7");
        RegulationSet truncate = regs(RegulationDefaults.ntaCertificate(1L));
        truncate.setGpaRounding(RegulationSet.Rounding.TRUNCATE);
        assertThat(Grading.gpa(truncate, new BigDecimal("110"), new BigDecimal("30"))).isEqualByComparingTo("3.6");
        assertThat(Grading.gpa(CERT, BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }

    @Test
    void classificationTakesTheHighestBandReached() {
        assertThat(Grading.classify(CERT, new BigDecimal("3.7"))).isEqualTo("First Class");
        assertThat(Grading.classify(CERT, new BigDecimal("3.45"))).isEqualTo("Second Class");
        assertThat(Grading.classify(CERT, new BigDecimal("1.9"))).isNull();
        assertThat(Grading.classify(DIPLOMA, new BigDecimal("4.0"))).isEqualTo("Upper Second Class");
    }

    @Test
    void decisionsFollowTheRegulations() {
        ModuleOutcome pass = outcome(Remark.PASS), fail = outcome(Remark.FAIL);
        assertThat(Grading.decide(CERT, List.of(pass, pass, pass), new BigDecimal("3"))).isEqualTo(Decision.PASS);
        assertThat(Grading.decide(CERT, List.of(pass, pass, fail), new BigDecimal("2.3"))).isEqualTo(Decision.SUPPLEMENTARY);
        // half the modules failed is not fewer than 50%: repeat, or discontinue below a GPA of 2.0
        assertThat(Grading.decide(CERT, List.of(pass, fail), new BigDecimal("2.0"))).isEqualTo(Decision.REPEAT);
        assertThat(Grading.decide(CERT, List.of(pass, fail), new BigDecimal("1.5"))).isEqualTo(Decision.DISCONTINUE);
        assertThat(Grading.decide(CERT, List.of(pass, outcome(Remark.ABSENT), fail), null)).isEqualTo(Decision.SPECIAL_EXAM);
        assertThat(Grading.decide(CERT, List.of(pass, outcome(Remark.INCOMPLETE)), null)).isEqualTo(Decision.INCOMPLETE);
    }

    @Test
    void theGradingScaleMustCoverEveryMarkOnce() {
        List<GradeBandDto> ok = RegulationDefaults.ntaCertificate(1L).gradeBands();
        RegulationService.checkBands(ok);
        List<GradeBandDto> gap = new ArrayList<>(ok);
        gap.set(1, new GradeBandDto("B", (short) 66, (short) 79, new BigDecimal("3"), null, true));
        assertThatThrownBy(() -> RegulationService.checkBands(gap)).isInstanceOf(BusinessRuleException.class).hasMessage("No grade covers marks 65–65");
        List<GradeBandDto> overlap = new ArrayList<>(ok);
        overlap.set(2, new GradeBandDto("C", (short) 50, (short) 66, new BigDecimal("2"), null, true));
        assertThatThrownBy(() -> RegulationService.checkBands(overlap)).hasMessageContaining("overlaps");
        List<GradeBandDto> noPass = ok.stream().map(b -> new GradeBandDto(b.grade(), b.minMark(), b.maxMark(), b.points(), null, false)).toList();
        assertThatThrownBy(() -> RegulationService.checkBands(noPass)).hasMessage("At least one grade must be a pass");
    }

    private static ModuleOutcome outcome(Remark r) {
        return new ModuleOutcome(null, null, null, null, null, r, null);
    }
}
