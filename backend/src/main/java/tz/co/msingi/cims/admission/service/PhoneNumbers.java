package tz.co.msingi.cims.admission.service;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

/** Tanzanian mobile numbers are stored in international form without "+", e.g. 255712345678. */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    public static String normalise(String input) {
        if (input == null) {
            throw invalid();
        }
        String digits = input.replaceAll("[\\s\\-()+]", "");
        if (digits.matches("0[67]\\d{8}")) {
            digits = "255" + digits.substring(1);
        } else if (digits.matches("[67]\\d{8}")) {
            digits = "255" + digits;
        }
        if (!digits.matches("255[67]\\d{8}")) {
            throw invalid();
        }
        return digits;
    }

    public static String mask(String phone) {
        return phone == null || phone.length() < 6 ? "***" : phone.substring(0, 5) + "****" + phone.substring(phone.length() - 3);
    }

    private static BusinessRuleException invalid() {
        return new BusinessRuleException("INVALID_PHONE", "Enter a valid Tanzanian mobile number, e.g. 0712 345 678");
    }
}
