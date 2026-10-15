package tz.co.msingi.cims.integration.nida;

import java.time.LocalDate;

/** Port to NIDA (FR-INT-06): checks a National Identification Number against the holder's names and date of birth. */
public interface NidaClient {

    /** VALID, MISMATCH (exists, but not this person), NOT_FOUND or INVALID (not a NIN). */
    record Result(String status, String firstName, String surname) {
        public boolean valid() {
            return "VALID".equals(status);
        }
    }

    Result verify(String nin, String firstName, String surname, LocalDate dateOfBirth);

    /** A NIN as 20 digits, or null when it cannot be one. */
    static String normalise(String nin) {
        if (nin == null) return null;
        String d = nin.replaceAll("[\\s-]", "");
        return d.matches("\\d{20}") ? d : null;
    }
}
