package tz.co.msingi.cims.integration.nactvet;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port to NACTVET admission verification (BP-04, FR-ADM-12, FR-INT-02), shaped as NOAS uses NACTVET's API.
 * <ul>
 *   <li>The institution <b>uploads</b> its selected applicants one programme at a time: a heading (intake, NACTVET programme id,
 *       application year, NTA level, the payment reference of the verification fee) and each applicant's particulars.</li>
 *   <li>NACTVET verifies them and publishes <b>verification feedback</b> per application year and intake, which the institution
 *       pulls and matches to the applicants still awaiting an outcome.</li>
 *   <li>Applicants admitted by more than one institution confirm one of them with a code NACTVET sends them by SMS.</li>
 * </ul>
 */
public interface NactvetGateway {

    /** Uploads one programme's applicants under one heading. Returns NACTVET's answer, kept with the batch. */
    String upload(Heading heading, List<Candidate> candidates);

    /**
     * Outcomes NACTVET has published so far for an application year and intake, for the candidates still awaiting one.
     * Candidates NACTVET has not decided yet are omitted, so the admissions office fetches again later.
     */
    List<Outcome> fetchOutcomes(String applicationYear, String intake, Collection<Candidate> submitted);

    /**
     * Confirms with NACTVET that a multiple-admission applicant chooses this institution,
     * using the confirmation code NACTVET sent them. Returns false if the code is wrong.
     */
    boolean confirmAdmission(String institutionCode, String indexNumber, String confirmationCode);

    /** The confirmation code, only in simulated mode, so it can be shown on screen during development. */
    default Optional<String> developmentConfirmationCode(String indexNumber) {
        return Optional.empty();
    }

    /**
     * The heading of an upload: one programme in one intake.
     *
     * @param intake           SEPTEMBER or MARCH
     * @param programmeId      NACTVET's programme id (the programme's NACTVET code in CIMS)
     * @param applicationYear  the calendar year the intake starts, e.g. 2026
     * @param level            NTA level digits, e.g. 6
     * @param paymentReference the payment reference (control number) of the verification fee paid to NACTVET
     */
    record Heading(String intake, String programmeId, String applicationYear, String level, String paymentReference) {
    }

    /** One selected applicant: what CIMS needs to match the outcome, and NACTVET's particulars. */
    record Candidate(String applicationRef, String indexNumber, Integer examYear, String firstName, String middleName,
                     String surname, String sex, LocalDate dateOfBirth, String nationality, String phone,
                     String programmeCode, String programmeName, int awardLevel, String remarks, Background background) {
    }

    /** The rest of NACTVET's particulars: impairment, A-level and NTA 4/5 awards, contacts and next of kin. */
    record Background(String disability, String formSixIndex, Integer formSixYear, String nta4Reg, Integer nta4Year,
                      String nta5Reg, Integer nta5Year, String email, String address, String region, String district,
                      String nextOfKinName, String nextOfKinAddress, String nextOfKinEmail, String nextOfKinPhone,
                      String nextOfKinRegion, String nextOfKinRelation) {

        public static final Background NONE = new Background(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    enum Result { VERIFIED, QUERIED, REJECTED }

    record Outcome(String applicationRef, Result result, String reason, String verificationNo, boolean multipleAdmission) {
    }
}
