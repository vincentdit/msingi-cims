package tz.co.msingi.cims.admission.letter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Everything printed on an admission letter, frozen when the letter is issued. Reprints and the
 * public verification page use this snapshot, never live data, so they always match what was issued.
 */
public record LetterContent(
        String institutionName,
        String institutionShortName,
        String institutionNactvetNo,
        String institutionPhone,
        String institutionEmail,
        String primaryColor,
        String letterNo,
        int edition,
        LocalDate letterDate,
        String applicantName,
        String applicantAddress,
        String applicationRef,
        String nactvetVerificationNo,
        String programmeName,
        String awardName,
        String programmeNactvetCode,
        int durationSemesters,
        String academicYear,
        String intake,
        OffsetDateTime acceptedAt,
        JoiningInstructions joining,
        String verificationCode,
        String verificationUrl,
        /* the institution's letter wording at issue (FR-ADM-S05); absent on letters issued before it could be edited */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        java.util.Map<String, String> wording) {

    public LetterContent(String institutionName, String institutionShortName, String institutionNactvetNo, String institutionPhone,
                         String institutionEmail, String primaryColor, String letterNo, int edition, LocalDate letterDate, String applicantName,
                         String applicantAddress, String applicationRef, String nactvetVerificationNo, String programmeName, String awardName,
                         String programmeNactvetCode, int durationSemesters, String academicYear, String intake, OffsetDateTime acceptedAt,
                         JoiningInstructions joining, String verificationCode, String verificationUrl) {
        this(institutionName, institutionShortName, institutionNactvetNo, institutionPhone, institutionEmail, primaryColor, letterNo, edition,
                letterDate, applicantName, applicantAddress, applicationRef, nactvetVerificationNo, programmeName, awardName, programmeNactvetCode,
                durationSemesters, academicYear, intake, acceptedAt, joining, verificationCode, verificationUrl, null);
    }

    public LetterContent withWording(java.util.Map<String, String> w) {
        return new LetterContent(institutionName, institutionShortName, institutionNactvetNo, institutionPhone, institutionEmail, primaryColor,
                letterNo, edition, letterDate, applicantName, applicantAddress, applicationRef, nactvetVerificationNo, programmeName, awardName,
                programmeNactvetCode, durationSemesters, academicYear, intake, acceptedAt, joining, verificationCode, verificationUrl, w);
    }
}
