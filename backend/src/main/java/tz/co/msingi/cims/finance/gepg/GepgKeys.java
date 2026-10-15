package tz.co.msingi.cims.finance.gepg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The keys of the live GePG connection: CIMS's private key (a PKCS#12 keystore) to sign what it sends, and GePG's public
 * certificate to check what GePG sends (bill acknowledgements, control numbers, payments, reconciliations). Read once at
 * start-up; CIMS refuses to start in live mode without them. Also loaded when a keystore is configured in simulated mode, so
 * GePG's signed callbacks can be tested before bills go live.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${cims.gepg.mode:simulated}' == 'live' or '${cims.gepg.live.keystore:}' != ''")
public class GepgKeys {

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
