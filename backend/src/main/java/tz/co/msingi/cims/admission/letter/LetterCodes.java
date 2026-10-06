package tz.co.msingi.cims.admission.letter;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * Verification codes printed on documents: 16 random characters (80 bits) in Crockford base 32,
 * shown as XXXX-XXXX-XXXX-XXXX. Unambiguous when read aloud or typed: no I, L, O or U, and
 * typing O for 0 or I/L for 1 is accepted.
 */
public final class LetterCodes {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private LetterCodes() {
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Normalises what a person typed or a scanner read; empty if it cannot be a code. */
    public static Optional<String> normalise(String input) {
        if (input == null) return Optional.empty();
        StringBuilder raw = new StringBuilder();
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) continue;
            char m = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
            if (new String(ALPHABET).indexOf(m) < 0) return Optional.empty();
            raw.append(m);
        }
        if (raw.length() != 16) return Optional.empty();
        return Optional.of(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12));
    }
}
