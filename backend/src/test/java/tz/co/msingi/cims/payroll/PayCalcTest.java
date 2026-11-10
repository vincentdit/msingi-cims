package tz.co.msingi.cims.payroll;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-PAY-02: PAYE by the resident bands, pension before tax, NHIF, HESLB, loans that stop at their balance, and the employer's costs. */
class PayCalcTest {

    static final List<PayCalc.Band> BANDS = List.of(band(0, 0), band(270_000, 8), band(520_000, 20), band(760_000, 25), band(1_000_000, 30));

    static PayCalc.Band band(long from, int rate) {
        return new PayCalc.Band(BigDecimal.valueOf(from), BigDecimal.valueOf(rate));
    }

    static PayCalc.Rates nssf(boolean nhif) {
        return new PayCalc.Rates("NSSF", BigDecimal.TEN, BigDecimal.TEN, false, BANDS, new BigDecimal("0.5"), false, new BigDecimal("3.5"), nhif,
                BigDecimal.valueOf(3), BigDecimal.valueOf(3), BigDecimal.valueOf(15));
    }

    @Test
    void payeFollowsTheBands() {
        assertThat(PayCalc.paye(BigDecimal.valueOf(270_000), BANDS)).isEqualByComparingTo("0");
        assertThat(PayCalc.paye(BigDecimal.valueOf(520_000), BANDS)).isEqualByComparingTo("20000");
        assertThat(PayCalc.paye(BigDecimal.valueOf(760_000), BANDS)).isEqualByComparingTo("68000");
        assertThat(PayCalc.paye(BigDecimal.valueOf(1_000_000), BANDS)).isEqualByComparingTo("128000");
        assertThat(PayCalc.paye(BigDecimal.valueOf(2_000_000), BANDS)).isEqualByComparingTo("428000");
        // the order the bands are given in does not matter
        assertThat(PayCalc.paye(BigDecimal.valueOf(760_000), BANDS.reversed())).isEqualByComparingTo("68000");
    }

    @Test
    void aMonthsPay() {
        List<PayCalc.Item> items = List.of(
                new PayCalc.Item(1L, "ALLOWANCE", "Housing", BigDecimal.valueOf(200_000), true, null),
                new PayCalc.Item(2L, "ALLOWANCE", "Meals", BigDecimal.valueOf(50_000), false, null),
                new PayCalc.Item(3L, "DEDUCTION", "Salary advance", BigDecimal.valueOf(100_000), true, BigDecimal.valueOf(60_000)),
                new PayCalc.Item(4L, "DEDUCTION", "SACCOS", BigDecimal.valueOf(30_000), true, null));
        PayCalc.Result r = PayCalc.compute(nssf(true), BigDecimal.valueOf(1_000_000), true, items);
        assertThat(r.gross()).isEqualByComparingTo("1250000");
        // NSSF on gross pay (10%), deducted before tax
        assertThat(r.pensionEmployee()).isEqualByComparingTo("125000");
        // taxable: basic + housing − pension = 1,075,000 → 128,000 + 30% of 75,000
        assertThat(r.taxableIncome()).isEqualByComparingTo("1075000");
        assertThat(r.paye()).isEqualByComparingTo("150500");
        assertThat(r.nhifEmployee()).isEqualByComparingTo("30000");
        assertThat(r.heslb()).isEqualByComparingTo("150000");
        // the advance stops at what is still owed
        assertThat(r.otherDeductions()).isEqualByComparingTo("90000");
        assertThat(r.net()).isEqualByComparingTo("704500");
        assertThat(r.pensionEmployer()).isEqualByComparingTo("125000");
        assertThat(r.wcf()).isEqualByComparingTo("6250");
        assertThat(r.sdl()).isEqualByComparingTo("0");
        assertThat(r.lines()).extracting(PayCalc.Line::amount).map(BigDecimal::toPlainString).containsExactly("200000", "50000", "60000", "30000");
    }

    @Test
    void psssfIsOnBasicSalary() {
        PayCalc.Rates psssf = new PayCalc.Rates("PSSSF", BigDecimal.valueOf(5), BigDecimal.valueOf(15), true, BANDS, new BigDecimal("0.5"), true,
                new BigDecimal("3.5"), false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(15));
        PayCalc.Result r = PayCalc.compute(psssf, BigDecimal.valueOf(800_000), false,
                List.of(new PayCalc.Item(1L, "ALLOWANCE", "Responsibility", BigDecimal.valueOf(100_000), true, null)));
        assertThat(r.pensionEmployee()).isEqualByComparingTo("40000");
        assertThat(r.pensionEmployer()).isEqualByComparingTo("120000");
        // 900,000 − 40,000 = 860,000 → 68,000 + 25% of 100,000
        assertThat(r.paye()).isEqualByComparingTo("93000");
        assertThat(r.sdl()).isEqualByComparingTo("31500");
        assertThat(r.heslb()).isEqualByComparingTo("0");
    }
}
