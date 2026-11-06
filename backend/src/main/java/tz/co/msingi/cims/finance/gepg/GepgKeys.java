package tz.co.msingi.cims.finance.gepg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The keys of the live GePG connection: CIMS's private key (a PKCS#12 keystore) to sign what it sends, and GePG's public
 * certificate to check what GePG sends (bill acknowledgements, control numbers, payments, reconciliations). Read once at
 * start-up; CIMS refuses to start in live mode without them. In simulated mode they are loaded only when the keystore and
 * GePG's certificate are both there and readable, so GePG's signed callbacks can be tested before bills go live; paths left
 * over from the production template, pointing at files that are not there, no longer stop a simulated server from starting.
 */
@Slf4j
@Component
@Conditional(GepgKeys.Wanted.class)
public class GepgKeys {

    /** Live mode: always (and a missing key stops start-up). Simulated mode: only when both files can be read. */
    static class Wanted implements Condition {
        private static final Logger LOG = LoggerFactory.getLogger(GepgKeys.class);

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if ("live".equalsIgnoreCase(env.getProperty("cims.gepg.mode", "simulated").trim())) return true;
            String keystore = env.getProperty("cims.gepg.live.keystore", "").trim();
            if (keystore.isEmpty()) return false;
            String certificate = env.getProperty("cims.gepg.live.gepg-certificate", "").trim();
            if (readable(keystore) && readable(certificate)) return true;
            LOG.warn("GePG is simulated: the keys at {} and {} are not readable, so signed GePG callbacks cannot be tested. "
                    + "Leave CIMS_GEPG_KEYSTORE and CIMS_GEPG_CERTIFICATE empty until the keys are installed.", keystore, certificate);
            return false;
        }

        static boolean readable(String path) {
            return path != null && !path.isBlank() && Files.isReadable(Path.of(path));
        }
    }

    private final GepgCrypto crypto;

    public GepgKeys(@Value("${cims.gepg.live.keystore:}") String keystore, @Value("${cims.gepg.live.keystore-password:}") String password,
                    @Value("${cims.gepg.live.key-alias:}") String alias, @Value("${cims.gepg.live.gepg-certificate:}") String certificate) {
        if (keystore.isBlank() || password.isBlank() || alias.isBlank() || certificate.isBlank()) {
            throw new IllegalStateException("cims.gepg.mode=live needs CIMS_GEPG_KEYSTORE, CIMS_GEPG_KEYSTORE_PASSWORD, CIMS_GEPG_KEY_ALIAS and "
                    + "CIMS_GEPG_CERTIFICATE (GePG's public certificate)");
        }
        this.crypto = GepgCrypto.load(keystore, password, alias, certificate);
        if (keystore.contains("test")) log.warn("GePG: signing with {} — a TEST-ONLY key. Never use it with the real GePG.", keystore);
    }

    public String signed(String content) {
        return crypto.message(content);
    }

    /** True when the message's content element is signed by GePG. */
    public boolean fromGepg(String xml, String contentTag) {
        return crypto.verify(GepgXml.inner(xml, contentTag), GepgXml.signature(xml));
    }

    public String ack(String tag, int code) {
        return signed("<" + tag + ">" + GepgXml.el("TrxStsCode", code) + "</" + tag + ">");
    }
}
