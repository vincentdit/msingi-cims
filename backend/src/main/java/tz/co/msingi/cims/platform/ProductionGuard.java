package tz.co.msingi.cims.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * With the {@code prod} profile, CIMS refuses to start on settings that are only safe in development (NFR-SEC-03): a missing
 * data-encryption key, generated sign-in keys (every restart would sign everyone out, and two servers would not trust each
 * other's tokens), default callback secrets, verification codes returned in responses, the demo institution, or the
 * test-only GePG keys.
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionGuard {

    private final Environment env;

    public ProductionGuard(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void check() {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("Production setting: {}", p));
            throw new IllegalStateException("CIMS will not start in production with unsafe settings: " + String.join("; ", problems));
        }
        log.info("Production settings checked");
    }

    static List<String> problems(Environment env) {
        List<String> p = new ArrayList<>();
        if (blank(env.getProperty("cims.security.data-key"))) p.add("CIMS_DATA_KEY (32 random bytes, base64) is not set");
        if ("local".equalsIgnoreCase(env.getProperty("cims.security.mode", "local"))
                && (blank(env.getProperty("cims.security.private-key-pem")) || blank(env.getProperty("cims.security.public-key-pem")))) {
            p.add("CIMS_JWT_PRIVATE_KEY and CIMS_JWT_PUBLIC_KEY are not set");
        }
        if (env.getProperty("cims.otp.expose-in-response", Boolean.class, false)) p.add("cims.otp.expose-in-response must be false");
        if (env.getProperty("cims.demo.seed", Boolean.class, false)) p.add("cims.demo.seed must be false");
        for (String k : List.of("cims.gepg.callback-secret", "cims.collection.callback-secret", "cims.sms.callback-secret")) {
            String v = env.getProperty(k);
            if (v != null && (v.isBlank() || v.startsWith("change-me") || v.startsWith("test-"))) p.add(k + " still has its default value");
        }
        String ks = env.getProperty("cims.gepg.live.keystore", "");
        if (ks.contains("dev-keys") || ks.contains("-test.")) p.add("the GePG keystore is the TEST-ONLY development key");
        return p;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
