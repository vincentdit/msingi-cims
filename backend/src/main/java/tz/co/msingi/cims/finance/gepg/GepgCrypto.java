package tz.co.msingi.cims.finance.gepg;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/** Signs with our private key and checks the other side's signatures with its certificate (SHA1withRSA, as GePG uses). */
public final class GepgCrypto {

    public static final String ALGORITHM = "SHA1withRSA";

    private final PrivateKey key;
    private final PublicKey peer;

    public GepgCrypto(PrivateKey key, PublicKey peer) {
        this.key = key;
        this.peer = peer;
    }

    public static GepgCrypto load(String keystore, String password, String alias, String peerCertificate) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keystore))) {
                ks.load(in, password.toCharArray());
            }
            PrivateKey k = (PrivateKey) ks.getKey(alias, password.toCharArray());
            if (k == null) throw new IllegalStateException("No key " + alias + " in " + keystore);
            PublicKey p;
            try (InputStream in = Files.newInputStream(Path.of(peerCertificate))) {
                p = CertificateFactory.getInstance("X.509").generateCertificate(in).getPublicKey();
            }
            return new GepgCrypto(k, p);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the GePG keys (" + keystore + ", " + peerCertificate + "): " + e.getMessage(), e);
        }
    }

    public String sign(String content) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public boolean verify(String content, String signature) {
        if (content == null || signature == null || signature.isBlank()) return false;
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(peer);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /** A signed message: the content followed by its signature. */
    public String message(String content) {
        return GepgXml.envelope(content, sign(content));
    }
}
