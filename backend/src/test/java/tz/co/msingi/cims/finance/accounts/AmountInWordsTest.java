package tz.co.msingi.cims.finance.accounts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @Test
    void writesShillingAmountsInWords() {
        assertThat(ReceiptService.AmountInWords.tzs(new BigDecimal("620000.00"))).isEqualTo("Tanzanian shillings six hundred twenty thousand only");
        assertThat(ReceiptService.AmountInWords.tzs(new BigDecimal("1250015.50")))
                .isEqualTo("Tanzanian shillings one million two hundred fifty thousand fifteen and cents fifty only");
        assertThat(ReceiptService.AmountInWords.tzs(new BigDecimal("93"))).isEqualTo("Tanzanian shillings ninety-three only");
        assertThat(ReceiptService.AmountInWords.tzs(new BigDecimal("2000000000"))).isEqualTo("Tanzanian shillings two billion only");
    }
}
