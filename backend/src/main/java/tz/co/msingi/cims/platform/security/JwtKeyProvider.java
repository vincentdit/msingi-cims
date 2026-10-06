package tz.co.msingi.cims.platform.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA key pair used to sign tokens in local mode. In production supply
 * CIMS_JWT_PRIVATE_KEY / CIMS_JWT_PUBLIC_KEY (PEM) from a secrets store; otherwise a
 * temporary key pair is generated at start-up and tokens will not survive restarts.
 */
@Slf4j
@Getter
@Component
public class JwtKeyProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public JwtKeyProvider(CimsSecurityProperties props) {
        try {
            if (StringUtils.hasText(props.privateKeyPem()) && StringUtils.hasText(props.publicKeyPem())) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decode(props.privateKeyPem())));
                this.publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(decode(props.publicKeyPem())));
            } else {
                log.warn("No JWT signing keys configured - generating a temporary key pair (development only).");
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair pair = gen.generateKeyPair();
                this.privateKey = (RSAPrivateKey) pair.getPrivate();
                this.publicKey = (RSAPublicKey) pair.getPublic();
            }
        } catch (NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException e) {
            throw new IllegalStateException("Invalid JWT key configuration", e);
        }
    }

    private static byte[] decode(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
