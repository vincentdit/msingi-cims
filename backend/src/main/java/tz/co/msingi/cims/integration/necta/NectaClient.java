package tz.co.msingi.cims.integration.necta;

import java.util.List;
import java.util.Optional;

/** Port to NECTA results (CSEE / ACSEE) by index number and year. */
public interface NectaClient {

    Optional<NectaResult> fetchResults(ExamType exam, String indexNumber, int year);

    enum ExamType { CSEE, ACSEE }

    record NectaResult(ExamType exam, String indexNumber, int year, String candidateName, String centreName,
                       String division, Integer points, List<SubjectGrade> subjects) {
    }

    record SubjectGrade(String code, String name, String grade) {
    }
}
