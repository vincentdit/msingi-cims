package tz.co.msingi.cims.exams;

import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.exams.ExamDtos.ClassificationDto;
import tz.co.msingi.cims.exams.ExamDtos.GradeBandDto;
import tz.co.msingi.cims.exams.ExamDtos.RegulationRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * The indicative NACTVET-aligned configuration of Document 03 section 5, used to start an institution's regulations. Each
 * institution confirms its own values; D (40-49) is not a pass and leads to a supplementary examination.
 */
public final class RegulationDefaults {

    private RegulationDefaults() {
    }

    /** NTA levels 4-5: A 80-100 (4), B 65-79 (3), C 50-64 (2), D 40-49 (1), F 0-39 (0). */
    public static RegulationRequest ntaCertificate(Long effectiveYearId) {
        return request("NTA Levels 4–5 (certificates)", 4, 5, effectiveYearId, List.of(
                band("A", 80, 100, "4", "Excellent", true), band("B", 65, 79, "3", "Good", true), band("C", 50, 64, "2", "Average", true),
                band("D", 40, 49, "1", "Poor", false), band("F", 0, 39, "0", "Failure", false)), List.of(
                new ClassificationDto("First Class", d("3.5"), d("4.0")), new ClassificationDto("Second Class", d("3.0"), d("3.4")),
                new ClassificationDto("Pass", d("2.0"), d("2.9"))));
    }

    /** NTA level 6: A 75-100 (5), B+ 70-74 (4), B 65-69 (3), C 50-64 (2), D 40-49 (1), F 0-39 (0). */
    public static RegulationRequest ntaDiploma(Long effectiveYearId) {
        return request("NTA Level 6 (ordinary diploma)", 6, 6, effectiveYearId, List.of(
                band("A", 75, 100, "5", "Excellent", true), band("B+", 70, 74, "4", "Very good", true), band("B", 65, 69, "3", "Good", true),
                band("C", 50, 64, "2", "Average", true), band("D", 40, 49, "1", "Poor", false), band("F", 0, 39, "0", "Failure", false)), List.of(
                new ClassificationDto("First Class", d("4.4"), d("5.0")), new ClassificationDto("Upper Second Class", d("3.5"), d("4.3")),
                new ClassificationDto("Lower Second Class", d("2.7"), d("3.4")), new ClassificationDto("Pass", d("2.0"), d("2.6"))));
    }

    private static RegulationRequest request(String name, int from, int to, Long yearId, List<GradeBandDto> bands, List<ClassificationDto> classes) {
        return new RegulationRequest(name, Programme.AwardFamily.NTA, (short) from, (short) to, yearId, d("40"), d("60"), d("50"), d("50"),
                (short) 1, RegulationSet.Rounding.ROUND, d("50"), "C", d("2.0"),
                "Indicative values from the SRS (section 5): confirm against the institution's approved examination regulations.", bands, classes);
    }

    private static GradeBandDto band(String grade, int min, int max, String points, String remark, boolean passing) {
        return new GradeBandDto(grade, (short) min, (short) max, d(points), remark, passing);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }
}
