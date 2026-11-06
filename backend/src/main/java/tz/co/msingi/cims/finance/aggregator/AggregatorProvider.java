package tz.co.msingi.cims.finance.aggregator;

import java.util.List;

/**
 * The payment aggregators an institution can collect mobile money through, with the fields each one issues to a merchant and the
 * addresses CIMS uses unless the institution enters others. {@link #SIMULATED} is a rehearsal inside CIMS: no money moves.
 * <p>The three real adapters follow each aggregator's published API as known when they were written (November 2026); confirm them
 * against the documents the aggregator gives with the merchant account, and correct the addresses here or on the settings page.
 */
public enum AggregatorProvider {

    SIMULATED("Rehearsal (no real money)", List.of(), null, null, null, null, true, false),
    SELCOM("Selcom", List.of(new Field("vendor", "Vendor (till) id", false), new Field("apiKey", "API key", false),
            new Field("apiSecret", "API secret", true)),
            "https://apigwtest.selcommobile.com/v1", "https://apigw.selcommobile.com/v1", null, null, true, false),
    AZAMPAY("AzamPay", List.of(new Field("appName", "App name", false), new Field("clientId", "Client id", false),
            new Field("clientSecret", "Client secret", true), new Field("apiKey", "API key", true)),
            "https://sandbox.azampay.co.tz", "https://checkout.azampay.co.tz",
            "https://authenticator-sandbox.azampay.co.tz", "https://authenticator.azampay.co.tz", false, true),
    CLICKPESA("ClickPesa", List.of(new Field("clientId", "Client id", false), new Field("apiKey", "API key", true)),
            "https://api.clickpesa.com", "https://api.clickpesa.com", null, null, true, false);

    /** One credential the aggregator issues; secret ones are stored encrypted and never shown again. */
    public record Field(String name, String label, boolean secret) {
    }

    private final String label;
    private final List<Field> fields;
    private final String sandboxUrl;
    private final String liveUrl;
    private final String sandboxAuthUrl;
    private final String liveAuthUrl;
    private final boolean statusQuery;
    private final boolean needsNetwork;

    AggregatorProvider(String label, List<Field> fields, String sandboxUrl, String liveUrl, String sandboxAuthUrl, String liveAuthUrl,
                       boolean statusQuery, boolean needsNetwork) {
        this.label = label;
        this.fields = fields;
        this.sandboxUrl = sandboxUrl;
        this.liveUrl = liveUrl;
        this.sandboxAuthUrl = sandboxAuthUrl;
        this.liveAuthUrl = liveAuthUrl;
        this.statusQuery = statusQuery;
        this.needsNetwork = needsNetwork;
    }

    public String label() {
        return label;
    }

    public List<Field> fields() {
        return fields;
    }

    public String defaultUrl(boolean live) {
        return live ? liveUrl : sandboxUrl;
    }

    public String defaultAuthUrl(boolean live) {
        return live ? liveAuthUrl : sandboxAuthUrl;
    }

    /** Whether CIMS can ask the aggregator what became of a request (otherwise it waits for the notification). */
    public boolean statusQuery() {
        return statusQuery;
    }

    /** Whether the payer's network must be named with the request (AzamPay). */
    public boolean needsNetwork() {
        return needsNetwork;
    }
}
