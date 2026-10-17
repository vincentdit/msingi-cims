package tz.co.msingi.cims.integration.necta;

import java.util.List;
import java.util.Optional;

/**
 * Port to NECTA results (CSEE / ACSEE) by index number and year. The candidate's names and sex
 * come with the results and are what applicants register under (BR-04).
 */
public interface NectaClient {

    /**
     * The candidate's particulars and results, or empty when NECTA has no candidate with that index number
     * in that year. Throws {@link NectaUnavailableException} when NECTA cannot be reached.
     */
    Optional<NectaResult> fetchResults(ExamType exam, String indexNumber, int year);

    /** NECTA's numeric exam ids are 1 (CSEE) and 2 (ACSEE). */
    enum ExamType {
        CSEE(1), ACSEE(2);

        private final int nectaId;

        ExamType(int nectaId) {
            this.nectaId = nectaId;
        }

        public int nectaId() {
            return nectaId;
        }
    }

    /**
     * @param premNumber the candidate's PREM number: the unique number NECTA gives a pupil at primary school (the PSLE
     *                   registration) and keeps on later results; null when NECTA does not send it
     */
    record NectaResult(ExamType exam, String indexNumber, int year, String firstName, String middleName, String surname,
                       String sex, String centreName, String division, Integer points, List<SubjectGrade> subjects, String premNumber) {

        public NectaResult(ExamType exam, String indexNumber, int year, String firstName, String middleName, String surname,
                           String sex, String centreName, String division, Integer points, List<SubjectGrade> subjects) {
            this(exam, indexNumber, year, firstName, middleName, surname, sex, centreName, division, points, subjects, null);
        }

        public String candidateName() {
            return String.join(" ", java.util.stream.Stream.of(firstName, middleName, surname)
                    .filter(s -> s != null && !s.isBlank()).toList());
        }
    }

    record SubjectGrade(String code, String name, String grade) {
    }
}
