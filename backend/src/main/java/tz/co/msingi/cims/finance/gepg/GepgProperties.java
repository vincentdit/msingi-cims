package tz.co.msingi.cims.finance.gepg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** cims.gepg.* — mode=simulated (development) or live (GePG specification adapter, to be configured per institution). */
@ConfigurationProperties(prefix = "cims.gepg")
public record GepgProperties(String mode, String callbackSecret, Duration controlNumberValidity) {
    public GepgProperties {
        if (mode == null) mode = "simulated";
        if (controlNumberValidity == null) controlNumberValidity = Duration.ofDays(7);
    }

    public boolean isSimulated() {
        return "simulated".equalsIgnoreCase(mode);
    }
}
