package tz.co.msingi.cims.platform.security.mfa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts small secrets at rest (authenticator secrets) with AES-256-GCM, and signs short-lived values (sign-in challenges) with HMAC,
 * using cims.security.data-key (base64, 32 bytes; set from the secret store in production).
 */
@Slf4j
@Component
public class SecretCipher {

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${cims.security.data-key:}") String configured) throws Exception {
        if (configured == null || configured.isBlank()) {
            log.warn("cims.security.data-key is not set: using a development key. Set CIMS_DATA_KEY in production.");
            key = MessageDigest.getInstance("SHA-256").digest("cims-development-data-key".getBytes(StandardCharsets.UTF_8));
        } else {
            key = Base64.getDecoder().decode(configured.trim());
            if (key.length != 32) throw new IllegalStateException("cims.security.data-key must be 32 bytes, base64-encoded");
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String enc) {
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
