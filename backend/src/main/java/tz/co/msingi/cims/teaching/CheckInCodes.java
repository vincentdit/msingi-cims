package tz.co.msingi.cims.teaching;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Rotating six-digit check-in codes, computed like an authenticator app (RFC 6238 style): an HMAC of the session's secret and the
 * current 20-second step. The lecturer's screen shows the code of the current step; a student's code is accepted for the current
 * step and the two before it (up to a minute), enough to type it or sign in after scanning, too short to be much use when forwarded.
 */
final class CheckInCodes {

    static final int STEP_SECONDS = 20;
    static final int ACCEPTED_STEPS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CheckInCodes() {
    }

    static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static long step(Instant at) {
        return at.getEpochSecond() / STEP_SECONDS;
    }

    static int secondsLeft(Instant at) {
        return (int) (STEP_SECONDS - at.getEpochSecond() % STEP_SECONDS);
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Whether the code is one shown on the lecturer's screen within the accepted window. Compared in constant time. */
    static boolean matches(String secret, String code, Instant at) {
        long now = step(at);
        boolean ok = false;
        for (int i = 0; i < ACCEPTED_STEPS; i++) {
            ok |= MessageDigest.isEqual(code(secret, now - i).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
