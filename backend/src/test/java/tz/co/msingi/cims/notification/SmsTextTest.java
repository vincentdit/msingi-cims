package tz.co.msingi.cims.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsTextTest {

    @Test
    void countsPartsForGsmAndUnicode() {
        assertThat(SmsText.size("a".repeat(160))).isEqualTo(new SmsText.Size("GSM7", 160, 1));
        assertThat(SmsText.size("a".repeat(161)).parts()).isEqualTo(2);
        assertThat(SmsText.size("a".repeat(306)).parts()).isEqualTo(2);
        assertThat(SmsText.size("a".repeat(307)).parts()).isEqualTo(3);
        assertThat(SmsText.size("{".repeat(80))).isEqualTo(new SmsText.Size("GSM7", 160, 1)); // extended characters count twice
        assertThat(SmsText.size("Habari ✓")).isEqualTo(new SmsText.Size("UCS2", 8, 1));
        assertThat(SmsText.size("✓".repeat(71)).parts()).isEqualTo(2);
    }

    @Test
    void plainPunctuationKeepsMessagesInGsm() {
        String fancy = "Fees 2027/2028 — ODIT year 1 “paid”…";
        assertThat(SmsText.size(fancy).encoding()).isEqualTo("UCS2");
        assertThat(SmsText.plain(fancy)).isEqualTo("Fees 2027/2028 - ODIT year 1 \"paid\"...");
        assertThat(SmsText.size(SmsText.plain(fancy)).encoding()).isEqualTo("GSM7");
    }

    @Test
    void rendersAmountsAndDatesPerLanguage() {
        var v = java.util.Map.of("amount", new java.math.BigDecimal("620000.00"), "date", java.time.LocalDate.of(2027, 9, 27));
        assertThat(MessageRender.render("{amount} by {date}", v, "EN")).isEqualTo("TZS 620,000 by 27 September 2027");
        assertThat(MessageRender.render("{amount} kabla ya {date}", v, "SW")).isEqualTo("TZS 620,000 kabla ya 27 Septemba 2027");
    }
}
