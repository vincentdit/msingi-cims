package tz.co.msingi.cims.integration.nhif;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Port to NHIF's Student Registration API (v2), which an institution uses as an NHIF <i>registration agent</i>:
 * <ol>
 *   <li>register its students with NHIF (POST /students/register);</li>
 *   <li>apply for their cards in a batch (POST /student/cardapplication) — NHIF answers each item with a control number and the
 *       amount to pay, by callback or when asked (GET /studentcontrolnobybatchno);</li>
 *   <li>NHIF reports the payment and the issued card by callback (handled by the institution's callback endpoint);</li>
 *   <li>check a card a student already has (GET /verifystudentcard), list the agent's intakes (GET /agentintakes), and ask NHIF
 *       to change a record (POST /student/changerequest).</li>
 * </ol>
 * Every call is made with the institution's own credentials ({@link Agent}).
 */
public interface NhifClient {

    /** The institution's credentials as an NHIF registration agent (OAuth2 client credentials and its system numbers). */
    record Agent(String clientId, String clientSecret, String scope, int systemId, int subSystemId) {
        @Override
        public String toString() {
            return "Agent[" + clientId + "]";   // never the secret, in logs or errors
        }
    }

    /** One of NHIF's error codes (2xxx validation, 3xxx business rules, 4xxx agent authorization, 5xxx system). */
    record Error(Integer code, String field, String message) {
    }

    /** An item NHIF did not take: its position in the request, the identifier sent (index number or correlation id) and why. */
    record Failure(int index, String identifier, List<Error> errors) {
        public String reason() {
            return errors == null || errors.isEmpty() ? "NHIF did not give a reason"
                    : errors.stream().map(e -> (e.code() == null ? "" : e.code() + " ") + (e.message() == null ? e.field() : e.message()))
                    .collect(Collectors.joining("; "));
        }
    }

    /** NHIF's BulkOperationResult: how many items were taken and the failures. A card application is all-or-nothing. */
    record BulkResult(int succeeded, List<Failure> failed, int totalRequested) {
        public boolean fullSuccess() {
            return failed == null || failed.isEmpty();
        }
    }

    /** A student to register with NHIF (CreateStudentDto). */
    record Registration(String firstName, String middleName, String surname, String gender, LocalDate dateOfBirth, String email, String mobileNo,
                        String nationalId, String formFourIndexNo, String admissionNo, int educationLevelId, String programmeOfStudy,
                        String collegeFaculty, Integer yearOfStudy, String academicYear, String intakeCode, String photoBase64) {
    }

    /**
     * One student in a card application (CardApplicationDetails). Category 1: a new card for a student NHIF holds (found by the NIDA
     * number); category 2: a student who already has an NHIF student card (by card number).
     */
    record CardApplicationItem(String correlationId, String nationalId, String mobileNo, String intakeCode, String cardNo, int educationLevelId,
                               int yearOfStudy, int category) {
    }

    /** NHIF's answer about a card: {@code remarks} is the card's state (ACTIVE when valid), or {@code error} when it is not known. */
    record CardCheck(String cardNo, String fullName, String remarks, Integer contributionEnds, String verificationCode, Error error) {
        public boolean active() {
            return error == null && remarks != null && remarks.trim().equalsIgnoreCase("ACTIVE");
        }
    }

    /** An academic intake NHIF has for the agent (AcademicIntakeDto). */
    record Intake(String name, String code, LocalDate startDate, LocalDate endDate) {
    }

    /** NHIF's control number for one card application item (AgentControlNoRequestDto). */
    record ControlNumber(String correlationId, String cardNo, String batchNo, String mobileNo, String invoiceNo, String controlNo, BigDecimal amount,
                         Integer category, String membershipNo) {
    }

    BulkResult registerStudents(Agent agent, List<Registration> students);

    BulkResult applyForCards(Agent agent, String batchNo, String intakeCode, String description, List<CardApplicationItem> items);

    /** Checks a student card; a 20-digit NIDA number may be given instead of the card number. */
    CardCheck verifyStudentCard(Agent agent, String cardNoOrNin);

    List<Intake> intakes(Agent agent);

    List<ControlNumber> controlNumbers(Agent agent, String batchNo);

    BulkResult changeRequest(Agent agent, int category, String cardNo, String academicYear, String formFourIndexNo);

    /** A card number as NHIF writes it: digits and letters only, upper case; null when nothing is left. */
    static String normalise(String cardNo) {
        if (cardNo == null) return null;
        String c = cardNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return c.length() < 6 || c.length() > 20 ? null : c;
    }

    /** The Form Four index number as NHIF wants it: SNNNN-NNNN-YYYY (e.g. S0101/0001 of 2023 → S0101-0001-2023). */
    static String formFourIndex(String indexNumber, Integer year) {
        if (indexNumber == null || year == null) return null;
        String i = indexNumber.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!i.matches("[SP]\\d{8}")) return null;
        return i.substring(0, 5) + "-" + i.substring(5) + "-" + year;
    }

    /** NHIF's intake code: the year and month studies start (YYYYMM). */
    static String intakeCode(int year, int month) {
        return String.format("%04d%02d", year, month);
    }

    /**
     * A card application batch number in NHIF's form id/startYear/endYear/serial: the institution's NHIF number, the academic
     * year (two consecutive years) and a serial of the institution's own.
     */
    static String batchNo(String agentNo, String academicYear, String serial) {
        String id = agentNo == null ? "0" : agentNo.replaceAll("[^A-Za-z0-9]", "");
        int start = Integer.parseInt(academicYear.substring(0, 4));
        return id + "/" + start + "/" + (start + 1) + "/" + serial;
    }
}
