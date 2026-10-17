package tz.co.msingi.cims.integration.nhif;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Port to NHIF (National Health Insurance Fund) for students' health insurance: checking a member's card, registering students
 * under NHIF's student package in batches, and following each batch until NHIF has issued the cards or said why not.
 */
public interface NhifClient {

    /**
     * What NHIF says about a card.
     *
     * @param status ACTIVE, INACTIVE (expired, suspended or not paid up) or NOT_FOUND
     */
    record Card(String cardNo, String status, String fullName, String scheme, LocalDate validTo, String remarks) {
    }

    /** A student to register under the student package. */
    record Student(String registrationNo, String firstName, String middleName, String surname, String sex, LocalDate dateOfBirth,
                   String nin, String phone, String email, String programmeName, int yearOfStudy) {
    }

    record Rejected(String registrationNo, String reason) {
    }

    /** NHIF's receipt for a batch: students it could not even take (e.g. missing date of birth) are rejected straight away. */
    record Submission(String batchRef, int received, List<Rejected> rejected) {
    }

    /** @param status REGISTERED (card issued), REJECTED or PENDING */
    record Item(String registrationNo, String status, String cardNo, LocalDate validTo, String reason) {
    }

    /** @param state PROCESSING or COMPLETED */
    record Status(String state, List<Item> items) {
    }

    Card verifyCard(String cardNo);

    Submission registerStudents(String institutionCode, String academicYear, List<Student> students);

    Status registrationStatus(String institutionCode, String batchRef);

    /** A card number as NHIF writes it: digits and letters only, upper case; null when nothing is left. */
    static String normalise(String cardNo) {
        if (cardNo == null) return null;
        String c = cardNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return c.length() < 6 || c.length() > 20 ? null : c;
    }
}
