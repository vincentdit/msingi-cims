package tz.co.msingi.cims.notification.sms;

import java.util.List;

/**
 * The Tanzanian SMS providers CIMS can send through, with the fields each one issues to an account holder and the addresses CIMS
 * uses unless others are entered in the Msingi console.
 * <p>The adapters follow each provider's published API as known when they were written (November 2026); confirm them against the
 * documents the provider gives with the account (NextSMS: nextsms.co.tz/api-docs, API v2; Beem: the developer portal) and correct the
 * addresses on the console page if they differ.
 */
public enum SmsProvider {

    NEXTSMS("NextSMS", List.of(new Field("apiToken", "API token (Customer Profile › Customization › API Keys)", true)),
            "https://messaging-service.co.tz", null),
    BEEM("Beem Africa", List.of(new Field("apiKey", "API key", false), new Field("secretKey", "Secret key", true)),
            "https://apisms.beem.africa", "https://dlrapi.beem.africa");

    /** One credential the provider issues; secret ones are stored encrypted and never shown again. */
    public record Field(String name, String label, boolean secret) {
    }

    private final String label;
    private final List<Field> fields;
    private final String baseUrl;
    private final String reportUrl;

    SmsProvider(String label, List<Field> fields, String baseUrl, String reportUrl) {
        this.label = label;
        this.fields = fields;
        this.baseUrl = baseUrl;
        this.reportUrl = reportUrl;
    }

    public String label() {
        return label;
    }

    public List<Field> fields() {
        return fields;
    }

    public String defaultBaseUrl() {
        return baseUrl;
    }

    /** Where delivery reports are asked for, when the provider keeps them on a separate address (Beem). */
    public String defaultReportUrl() {
        return reportUrl;
    }
}
