package tz.co.msingi.cims.integration.nactvet;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port to NACTVET admission verification (BP-04, FR-ADM-12, FR-INT-02).
 * The institution submits its selected applicants; NACTVET verifies each one against its
 * records and entry requirements and returns an outcome. Applicants admitted by more than one
 * institution confirm one of them with a code NACTVET sends them by SMS.
 */
public interface NactvetGateway {

    /** Submits candidates for verification and returns NACTVET's reference for the batch. */
    String submit(String institutionCode, List<Candidate> candidates);

    /**
     * Outcomes available so far for a submitted batch. Candidates still being processed are omitted.
     * {@code submitted} lists the candidates still awaiting an outcome, for adapters that query per candidate.
     */
    List<Outcome> fetchOutcomes(String externalRef, Collection<Candidate> submitted);

    /**
     * Confirms with NACTVET that a multiple-admission applicant chooses this institution,
     * using the confirmation code NACTVET sent them. Returns false if the code is wrong.
     */
    boolean confirmAdmission(String institutionCode, String indexNumber, String confirmationCode);

    /** The confirmation code, only in simulated mode, so it can be shown on screen during development. */
    default Optional<String> developmentConfirmationCode(String indexNumber) {
        return Optional.empty();
    }

    /** One selected applicant in NACTVET's admission verification template. */
    record Candidate(String applicationRef, String indexNumber, Integer examYear, String firstName, String middleName,
                     String surname, String sex, LocalDate dateOfBirth, String nationality, String phone,
                     String programmeCode, String programmeName, int awardLevel, String remarks) {
    }

    enum Result { VERIFIED, QUERIED, REJECTED }

    record Outcome(String applicationRef, Result result, String reason, String verificationNo, boolean multipleAdmission) {
    }
}
