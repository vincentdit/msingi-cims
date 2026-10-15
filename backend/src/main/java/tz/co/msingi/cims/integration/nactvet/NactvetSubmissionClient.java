package tz.co.msingi.cims.integration.nactvet;

import java.math.BigDecimal;
import java.util.List;

/**
 * Port to NACTVET for student registration (FR-REG-07) and results submission (FR-EXM-11) by API. Both are batches: CIMS
 * sends, NACTVET answers with a reference, and the outcome is fetched later.
 */
public interface NactvetSubmissionClient {

    record RegStudent(String registrationNo, String verificationNumber, String indexNumber, String firstName, String middleName, String surname,
                      String sex, String dateOfBirth, String nationality, String phone, String programmeCode, int yearOfStudy) {
    }

    record ModuleGrade(String code, String grade) {
    }

    record ResultRow(String registrationNo, String verificationNumber, List<ModuleGrade> modules, BigDecimal gpa, String remark) {
    }

    record Results(String programmeCode, String academicYear, String semester, int yearOfStudy, String round, List<ResultRow> rows) {
    }

    /** One student's outcome: REGISTERED / ACCEPTED, QUERIED (with the reason) or PROCESSING. */
    record ItemStatus(String registrationNo, String status, String reason, String nactvetNumber) {
    }

    /** The batch: PROCESSING, or COMPLETED (registration) / ACCEPTED or QUERIED (results), with the students decided so far. */
    record Status(String state, List<ItemStatus> items) {
    }

    String submitRegistrations(String institutionCode, String academicYear, List<RegStudent> students);

    Status registrationStatus(String batchRef);

    String submitResults(String institutionCode, Results results);

    Status resultStatus(String batchRef);
}
