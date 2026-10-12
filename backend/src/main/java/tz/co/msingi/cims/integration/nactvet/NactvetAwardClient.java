package tz.co.msingi.cims.integration.nactvet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Port to NACTVET's particulars service: an NTA award by registration number (e.g. NS0816/0129/2015) and
 * graduation year, with the graduate's names, the programme, NTA level, GPA, class of award and modules.
 * Throws {@link NactvetUnavailableException} when NACTVET cannot be reached or refuses the request.
 */
public interface NactvetAwardClient {

    Optional<NtaAward> fetchAward(String registrationNumber, int graduationYear);

    record NtaAward(String registrationNumber, int graduationYear, String firstName, String middleName, String surname,
                    String sex, String institution, String programme, Integer ntaLevel, BigDecimal gpa, String classAward,
                    String status, String formFourIndexNumber, List<Module> modules) {
    }

    record Module(String code, String name, String grade, Integer credits, String semester) {
    }

    /** "NTA6", "NTA Level 6", "6" → 6. */
    static Integer level(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        int n = Integer.parseInt(digits.substring(0, 1));
        return n >= 4 && n <= 8 ? n : null;
    }
}
