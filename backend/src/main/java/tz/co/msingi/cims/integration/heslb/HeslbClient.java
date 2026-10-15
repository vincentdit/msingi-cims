package tz.co.msingi.cims.integration.heslb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port to HESLB (FR-INT-05): the institution sends registration (first year) and continuation (later years) data; HESLB
 * answers with its loan beneficiaries for the year and the disbursements it has paid to the institution.
 */
public interface HeslbClient {

    record Student(String indexNumber, String registrationNo, String firstName, String middleName, String surname, String sex,
                   String programmeCode, String programmeName, int yearOfStudy, String status, String phone) {
    }

    record Rejected(String registrationNo, String reason) {
    }

    record SubmissionResult(String reference, int received, int accepted, List<Rejected> rejected) {
    }

    /** A loan beneficiary and what HESLB pays the institution for them (tuition and other institution-payable items). */
    record Beneficiary(String indexNumber, String registrationNo, String fullName, String loanNumber, BigDecimal toInstitution) {
    }

    record DisbursedStudent(String indexNumber, String registrationNo, String loanNumber, BigDecimal amount) {
    }

    record Disbursement(String batchNo, LocalDate disbursedOn, BigDecimal amount, List<DisbursedStudent> students) {
    }

    /** kind: REGISTRATION (first-year students) or CONTINUATION (continuing students). */
    SubmissionResult sendRegistrations(String institutionCode, String academicYear, String kind, List<Student> students);

    List<Beneficiary> beneficiaries(String institutionCode, String academicYear);

    List<Disbursement> disbursements(String institutionCode, String academicYear);
}
