package tz.co.msingi.cims.admission.service;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.time.Year;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A NECTA index number and examination year as applicants type them: {@code S0101/0001} with the year apart,
 * or all together as {@code S0101/0001/2023}; dashes, spaces and lower case are accepted.
 */
public record NectaIndex(String indexNumber, int year) {

    private static final Pattern FORM = Pattern.compile("^([SP])\\s*(\\d{4})\\s*[/\\-\\s]\\s*(\\d{4})(?:\\s*[/\\-\\s]\\s*(\\d{4}))?$");
    public static final int FIRST_YEAR = 1988;

    public static NectaIndex parse(String input, Integer year) {
        Matcher m = input == null ? null : FORM.matcher(input.trim().toUpperCase());
        if (m == null || !m.matches()) {
            throw new BusinessRuleException("INVALID_INDEX_NUMBER", "Enter your Form Four index number as on your certificate, e.g. S0101/0001");
        }
        Integer y = m.group(4) != null ? Integer.valueOf(m.group(4)) : year;
        if (y == null) {
            throw new BusinessRuleException("EXAM_YEAR_REQUIRED", "Enter the year you sat the examination");
        }
        int now = Year.now(ZoneId.of("Africa/Dar_es_Salaam")).getValue();
        if (y < FIRST_YEAR || y > now) {
            throw new BusinessRuleException("INVALID_EXAM_YEAR", "The examination year must be between " + FIRST_YEAR + " and " + now);
        }
        return new NectaIndex(m.group(1) + m.group(2) + "/" + m.group(3), y);
    }

    /** True if the text looks like an index number (with or without the year), e.g. for signing in. */
    public static boolean looksLikeIndex(String input) {
        return input != null && FORM.matcher(input.trim().toUpperCase()).matches();
    }

    /** The year typed with the index number, if any. */
    public static Integer embeddedYear(String input) {
        Matcher m = FORM.matcher(input.trim().toUpperCase());
        return m.matches() && m.group(4) != null ? Integer.valueOf(m.group(4)) : null;
    }

    public static String indexOnly(String input) {
        Matcher m = FORM.matcher(input.trim().toUpperCase());
        return m.matches() ? m.group(1) + m.group(2) + "/" + m.group(3) : null;
    }

    @Override
    public String toString() {
        return indexNumber + " (" + year + ")";
    }
}
