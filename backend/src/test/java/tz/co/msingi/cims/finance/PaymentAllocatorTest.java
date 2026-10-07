package tz.co.msingi.cims.finance;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.InvoiceLine;
import tz.co.msingi.cims.finance.domain.PaymentAllocator;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAllocatorTest {

    private static Invoice invoice() {
        Invoice inv = new Invoice();
        line(inv, "TUITION", 300_000, 50, 2);
        line(inv, "TUITION", 300_000, 50, 1);
        line(inv, "EXAM", 50_000, 30, 1);
        line(inv, "REG", 20_000, 10, 1);
        return inv;
    }

    private static void line(Invoice inv, String code, long amount, int priority, int instalment) {
        InvoiceLine l = inv.addLine(code, code, BigDecimal.valueOf(amount));
        l.setPriority((short) priority);
        l.setInstalment((short) instalment);
    }

    private static BigDecimal paid(Invoice inv, String code, int instalment) {
        return inv.getLines().stream().filter(l -> l.getFeeItemCode().equals(code) && l.getInstalment() == instalment)
                .findFirst().orElseThrow().getPaidAmount();
    }

    @Test
    void firstInstalmentThenPriority() {
        Invoice inv = invoice();
        PaymentAllocator.Result r = inv.applyPayment(BigDecimal.valueOf(100_000));
        assertThat(r.unallocated()).isZero();
        assertThat(paid(inv, "REG", 1)).isEqualByComparingTo("20000");
        assertThat(paid(inv, "EXAM", 1)).isEqualByComparingTo("50000");
        assertThat(paid(inv, "TUITION", 1)).isEqualByComparingTo("30000");
        assertThat(paid(inv, "TUITION", 2)).isZero();
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PARTIALLY_PAID);
    }

    @Test
    void overpaymentIsHeldAsCredit() {
        Invoice inv = invoice();
        inv.applyPayment(BigDecimal.valueOf(600_000));
        PaymentAllocator.Result r = inv.applyPayment(BigDecimal.valueOf(100_000));
        assertThat(r.unallocated()).isEqualByComparingTo("30000");
        assertThat(inv.getPaidAmount()).isEqualByComparingTo("670000");
        assertThat(inv.balance()).isZero();
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PAID);
        // a payment to an invoice already paid is credit in full
        assertThat(inv.applyPayment(BigDecimal.TEN).unallocated()).isEqualByComparingTo("10");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> invoice().applyPayment(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
