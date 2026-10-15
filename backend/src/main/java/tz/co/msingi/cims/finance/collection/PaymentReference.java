package tz.co.msingi.cims.finance.collection;

import java.security.SecureRandom;

/**
 * The 12-digit payment reference CIMS issues for an invoice of a private or faith-based institution: "8", the institution's id in four
 * digits, six random digits and a Luhn check digit. Payers quote it on mobile money and at the bank; the institution is read from it
 * when the collector reports the payment, and the check digit catches most typing mistakes.
 */
public final class PaymentReference {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PaymentReference() {
    }

    public static String issue(long tenantId) {
        if (tenantId <= 0 || tenantId > 9999) throw new IllegalArgumentException("Institution id out of range for payment references");
        String body = "8" + String.format("%04d", tenantId) + String.format("%06d", RANDOM.nextInt(1_000_000));
        return body + checkDigit(body);
    }

    public static boolean isValid(String ref) {
        if (ref == null) return false;
        String r = ref.replaceAll("\\s", "");
        return r.matches("8\\d{11}") && checkDigit(r.substring(0, 11)) == r.charAt(11) - '0';
    }

    /** The institution id inside a valid reference, or null. */
    public static Long tenantOf(String ref) {
        if (!isValid(ref)) return null;
        return Long.parseLong(ref.replaceAll("\\s", "").substring(1, 5));
    }

    static int checkDigit(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
