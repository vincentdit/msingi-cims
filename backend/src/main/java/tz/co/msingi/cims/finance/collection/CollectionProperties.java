package tz.co.msingi.cims.finance.collection;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cims.collection.* — how payments into private and faith-based institutions' own accounts are confirmed.
 * mode=simulated (development) or statement (production until a collector adapter, e.g. an aggregator, is added).
 * Payments can always be confirmed from the collector's or bank's statement on the reconciliation page.
 */
@ConfigurationProperties(prefix = "cims.collection")
public record CollectionProperties(String mode, String callbackSecret) {
    public CollectionProperties {
        if (mode == null) mode = "simulated";
        if (callbackSecret == null) callbackSecret = "change-me";
    }

    public boolean isSimulated() {
        return "simulated".equalsIgnoreCase(mode);
    }
}
