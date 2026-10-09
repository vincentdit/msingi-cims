package tz.co.msingi.cims.exams;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The frozen content of a statement of results / provisional transcript, rendered to PDF and shown on verification. */
public record TranscriptContent(
        String institutionName,
        String institutionShortName,
        String institutionNactvetNo,
        String institutionContact,
        String primaryColor,
        String documentNo,
        LocalDate issuedOn,
        String studentName,
        String registrationNo,
        String sex,
        LocalDate dateOfBirth,
        String nactvetVerificationNo,
        String programmeName,
        String awardName,
        String programmeNactvetCode,
        String entryYear,
        List<Semester> semesters,
        BigDecimal creditsEarned,
        BigDecimal cgpa,
        String classification,
        String regulation,
        String verificationCode,
        String verificationUrl) {

    public record Semester(String name, String academicYear, short yearOfStudy, boolean afterResits, List<Module> modules, BigDecimal credits,
                           BigDecimal creditsPassed, BigDecimal gpa, String decision) {
    }

    public record Module(String code, String title, BigDecimal credits, String grade, BigDecimal points, String remark, String attempt) {
    }
}
