package tz.co.msingi.cims.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** With the prod profile CIMS refuses development-only settings. */
class ProductionGuardTest {

    @Test
    void developmentSettingsAreRefusedAndProperOnesPass() {
        MockEnvironment dev = new MockEnvironment()
                .withProperty("cims.otp.expose-in-response", "true")
                .withProperty("cims.demo.seed", "true")
                .withProperty("cims.gepg.callback-secret", "change-me")
                .withProperty("cims.sms.callback-secret", "test-sms-secret")
                .withProperty("cims.gepg.live.keystore", "../dev-keys/cims-sp-test.p12");
        assertThat(ProductionGuard.problems(dev)).hasSize(7)
                .anyMatch(p -> p.contains("CIMS_DATA_KEY")).anyMatch(p -> p.contains("CIMS_JWT_PRIVATE_KEY")).anyMatch(p -> p.contains("TEST-ONLY"));

        MockEnvironment prod = new MockEnvironment()
                .withProperty("cims.security.data-key", "a2V5")
                .withProperty("cims.security.private-key-pem", "MIIE…")
                .withProperty("cims.security.public-key-pem", "MIIB…")
                .withProperty("cims.gepg.callback-secret", "5f8c0e1d")
                .withProperty("cims.collection.callback-secret", "9a7b6c5d")
                .withProperty("cims.sms.callback-secret", "1e2f3a4b")
                .withProperty("cims.gepg.live.keystore", "/run/secrets/cims/gepg-sp.p12");
        assertThat(ProductionGuard.problems(prod)).isEmpty();
    }
}
