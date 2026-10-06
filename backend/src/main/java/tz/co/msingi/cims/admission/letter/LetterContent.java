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
        String verificationUrl) {
}
