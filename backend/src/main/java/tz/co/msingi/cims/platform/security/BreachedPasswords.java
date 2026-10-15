package tz.co.msingi.cims.platform.security;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Passwords that meet the complexity rule but are among the most used or guessed (NFR-SEC-01): common words with the usual capital,
 * digits and symbol, including local ones (Tanzania@123, Simba@2024). Checked on sign-up, password change and reset.
 */
public final class BreachedPasswords {

    private static final Set<String> LIST = load();

    private BreachedPasswords() {
    }

    public static boolean isBreached(String password) {
        if (password == null) return false;
        String p = password.toLowerCase(Locale.ROOT);
        if (LIST.contains(p)) return true;
        // undo the usual letter-for-symbol swaps (P@ssw0rd -> password)
        String plain = p.replace('@', 'a').replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('$', 's').replace('5', 's');
        if (LIST.contains(plain)) return true;
        // a common word with only a short number or a year and symbols around it (Tanzania#2024, Simba!12)
        String word = p.replaceAll("[^a-z]", "");
        String digits = p.replaceAll("[^0-9]", "");
        boolean shortTail = digits.length() <= 4 || digits.matches("(19|20)\\d\\d\\d{0,2}");
        String head = deLeet(p.replaceAll("[0-9!#@$%^&*._+-]+$", "")).replaceAll("[^a-z]", "");
        return shortTail && (LIST.contains("@stem:" + word) || LIST.contains("@stem:" + head));
    }

    private static String deLeet(String p) {
        return p.replace('@', 'a').replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('$', 's').replace('5', 's');
    }

    private static Set<String> load() {
        Set<String> s = new HashSet<>();
        try (InputStream in = BreachedPasswords.class.getResourceAsStream("/security/common-passwords.txt")) {
            if (in == null) return s;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("stem:")) s.add("@stem:" + line.substring(5));
                    else s.add(line);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the common password list", e);
        }
        return s;
    }
}
