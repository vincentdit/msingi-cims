package tz.co.msingi.cims.integration.necta;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Development NECTA client returning deterministic results derived from the index number,
 * so the same index always gives the same grades. Index numbers ending in "/0000" are "not found".
 *
 * Handy test values (CSEE):  S0101/0001 → strong results (Division I);
 *                            S0101/0002 → weak results (fewer than four passes);
 *                            S0101/0000 → no such candidate.
 * Names, sex, school and PREM number are made up from the index number.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.necta", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNectaClient implements NectaClient {

    private static final String[][] CSEE_SUBJECTS = {
            {"011", "CIVICS"}, {"012", "HISTORY"}, {"013", "GEOGRAPHY"}, {"021", "KISWAHILI"}, {"022", "ENGLISH LANGUAGE"},
            {"031", "PHYSICS"}, {"032", "CHEMISTRY"}, {"033", "BIOLOGY"}, {"041", "BASIC MATHEMATICS"}};
    private static final String[][] ACSEE_SUBJECTS = {
            {"111", "GENERAL STUDIES"}, {"131", "PHYSICS"}, {"132", "CHEMISTRY"}, {"142", "ADVANCED MATHEMATICS"}};
    private static final String[] CSEE_GRADES = {"A", "B", "C", "D", "F"};
    private static final String[] ACSEE_GRADES = {"A", "B", "C", "D", "E", "S", "F"};

    @Override
    public Optional<NectaResult> fetchResults(ExamType exam, String indexNumber, int year) {
        String idx = indexNumber.trim().toUpperCase();
        if (idx.endsWith("/0000")) {
            return Optional.empty();
        }
        List<SubjectGrade> subjects = new ArrayList<>();
        if (idx.endsWith("/0001")) {
            for (String[] s : exam == ExamType.CSEE ? CSEE_SUBJECTS : ACSEE_SUBJECTS) {
                subjects.add(new SubjectGrade(s[0], s[1], s[1].contains("MATHEMATICS") ? "B" : "A"));
            }
            return Optional.of(result(exam, idx, year, "I", 9, subjects));
        }
        if (idx.endsWith("/0002")) {
            String[] grades = {"D", "F", "F", "C", "D", "F", "F", "F", "F"};
            String[][] list = exam == ExamType.CSEE ? CSEE_SUBJECTS : ACSEE_SUBJECTS;
            for (int i = 0; i < list.length; i++) {
                subjects.add(new SubjectGrade(list[i][0], list[i][1], exam == ExamType.CSEE ? grades[i] : "F"));
            }
            return Optional.of(result(exam, idx, year, "0", 33, subjects));
        }
        Random rnd = new Random((idx + year + exam).hashCode());
        String[][] list = exam == ExamType.CSEE ? CSEE_SUBJECTS : ACSEE_SUBJECTS;
        String[] scale = exam == ExamType.CSEE ? CSEE_GRADES : ACSEE_GRADES;
        for (String[] s : list) {
            subjects.add(new SubjectGrade(s[0], s[1], scale[Math.min(scale.length - 1, Math.abs(rnd.nextInt()) % 4 + (rnd.nextBoolean() ? 0 : 1))]));
        }
        String[] divisions = {"I", "II", "III", "IV"};
        return Optional.of(result(exam, idx, year, divisions[Math.abs(rnd.nextInt()) % 4], 7 + Math.abs(rnd.nextInt()) % 25, subjects));
    }

    private static final String[] FEMALE = {"Neema", "Asha", "Rehema", "Zawadi", "Halima", "Grace", "Mwanaidi", "Upendo", "Faraja", "Salma"};
    private static final String[] MALE = {"Baraka", "Juma", "Hassan", "Emmanuel", "Daudi", "Omari", "Joseph", "Said", "Frank", "Ally"};
    private static final String[] SURNAMES = {"Mushi", "Mwakyusa", "Kweka", "Massawe", "Lyimo", "Ngowi", "Mrema", "Kimaro", "Swai", "Mollel",
            "Mbwambo", "Temba", "Shirima", "Urassa", "Minja"};
    private static final String[] SCHOOLS = {"AZANIA SECONDARY SCHOOL", "MZUMBE SECONDARY SCHOOL", "KIBAHA SECONDARY SCHOOL",
            "TABORA GIRLS SECONDARY SCHOOL", "ILBORU SECONDARY SCHOOL", "MSALATO GIRLS SECONDARY SCHOOL"};

    /** Names, sex and school derived from the index number, so the same candidate always has the same particulars. */
    private static NectaResult result(ExamType exam, String idx, int year, String division, int points, List<SubjectGrade> subjects) {
        log.debug("[NECTA simulated] {} {} {}", exam, idx, year);
        Random r = new Random(idx.hashCode());
        boolean female = r.nextBoolean();
        String[] first = female ? FEMALE : MALE;
        return new NectaResult(exam, idx, year, first[r.nextInt(first.length)], MALE[r.nextInt(MALE.length)],
                SURNAMES[r.nextInt(SURNAMES.length)], female ? "F" : "M", SCHOOLS[r.nextInt(SCHOOLS.length)], division, points, subjects,
                prem(idx, exam == ExamType.CSEE ? year : year - 2));
    }

    /** A made-up PREM number: the year the candidate sat the primary school leaving examination and eight digits, always the same for one candidate. */
    static String prem(String idx, int cseeYear) {
        return String.format("%d%08d", cseeYear - 4, Math.floorMod(idx.toUpperCase().hashCode(), 100_000_000));
    }
}
