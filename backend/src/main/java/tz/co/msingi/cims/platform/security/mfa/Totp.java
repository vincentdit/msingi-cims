package tz.co.msingi.cims.platform.security.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/** Time-based one-time passwords (RFC 6238: HMAC-SHA1, 30-second steps, 6 digits), as Google and Microsoft Authenticator use. */
public final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new 160-bit secret in base32. */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    /** True when the code matches this step or one step either side (clock drift). */
    public static boolean verify(String base32Secret, String code, Instant now) {
        if (code == null || !code.trim().matches("\\d{6}")) return false;
        long step = now.getEpochSecond() / 30;
        for (long s = step - 1; s <= step + 1; s++) {
            if (code(base32Secret, s).equals(code.trim())) return true;
        }
        return false;
    }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String uri(String issuer, String account, String secret) {
        String i = enc(issuer), a = enc(account);
        return "otpauth://totp/" + i + ":" + a + "?secret=" + secret + "&issuer=" + i + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(in.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : in.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
