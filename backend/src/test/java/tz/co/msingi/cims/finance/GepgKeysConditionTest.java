package tz.co.msingi.cims.finance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tz.co.msingi.cims.finance.gepg.GepgKeys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GePG's keys are needed in live mode; in simulated mode they are loaded only when the files are there, so a production
 * template's key paths do not stop a simulated server from starting (the first pilot server failed this way).
 */
class GepgKeysConditionTest {

    private static final String KEYS = "../dev-keys/";
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GepgKeys.class);

    @Test
    void simulatedWithMissingKeyFilesStartsWithoutKeys() {
        runner.withPropertyValues("cims.gepg.mode=simulated", "cims.gepg.live.keystore=/run/secrets/cims/gepg-sp.p12",
                        "cims.gepg.live.keystore-password=x", "cims.gepg.live.key-alias=x",
                        "cims.gepg.live.gepg-certificate=/run/secrets/cims/gepg-public.crt")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(GepgKeys.class);
                });
    }

    @Test
    void simulatedWithoutKeysHasNoKeys() {
        runner.withPropertyValues("cims.gepg.mode=simulated").run(ctx -> assertThat(ctx).doesNotHaveBean(GepgKeys.class));
    }

    @Test
    void simulatedWithReadableKeysLoadsThemForCallbackTests() {
        runner.withPropertyValues("cims.gepg.mode=simulated", "cims.gepg.live.keystore=" + KEYS + "cims-sp-test.p12",
                        "cims.gepg.live.keystore-password=test-only-not-secret", "cims.gepg.live.key-alias=cims-sp",
                        "cims.gepg.live.gepg-certificate=" + KEYS + "gepg-mock-test.crt")
                .run(ctx -> assertThat(ctx).hasSingleBean(GepgKeys.class));
    }

    @Test
    void liveWithMissingKeysRefusesToStart() {
        runner.withPropertyValues("cims.gepg.mode=live", "cims.gepg.live.keystore=/run/secrets/cims/gepg-sp.p12",
                        "cims.gepg.live.keystore-password=x", "cims.gepg.live.key-alias=x",
                        "cims.gepg.live.gepg-certificate=/run/secrets/cims/gepg-public.crt")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
