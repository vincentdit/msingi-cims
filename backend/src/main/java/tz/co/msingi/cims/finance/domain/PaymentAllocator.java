package tz.co.msingi.cims.finance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Allocates a payment to invoice lines (FR-FIN-04): first instalment before later ones, then by the fee item's
 * allocation priority, then in line order. A line never receives more than its balance; the rest is unallocated.
 */
public final class PaymentAllocator {

    private PaymentAllocator() {
    }

    public record Allocation(InvoiceLine line, BigDecimal amount) {
    }

    public record Result(List<Allocation> allocations, BigDecimal unallocated) {
    }

    public static final Comparator<InvoiceLine> ORDER = Comparator.comparing((InvoiceLine l) -> l.getInstalment())
            .thenComparing(l -> l.getPriority())
            .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId());

    public static Result allocate(List<InvoiceLine> lines, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        BigDecimal left = amount;
        List<Allocation> out = new ArrayList<>();
        for (InvoiceLine l : lines.stream().sorted(ORDER).toList()) {
            if (left.signum() == 0) break;
            BigDecimal due = l.balance();
            if (due.signum() <= 0) continue;
            BigDecimal take = due.min(left);
            out.add(new Allocation(l, take));
            left = left.subtract(take);
        }
        return new Result(out, left);
    }
}
