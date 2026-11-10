package tz.co.msingi.cims.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One month's pay for one person (FR-PAY-02), worked out from the statutory rates: gross pay; the employee's pension contribution
 * (deductible for PAYE); PAYE on the taxable income by the bands; NHIF where it applies; the HESLB loan repayment on basic salary;
 * the standing deductions (a loan stops at its balance); and the employer's pension, NHIF, WCF and SDL. Amounts are whole shillings.
 */
public final class PayCalc {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Band(BigDecimal from, BigDecimal rate) {
    }

    public record Rates(String pensionFund, BigDecimal pensionEmployeeRate, BigDecimal pensionEmployerRate, boolean pensionOnBasic, List<Band> payeBands,
                        BigDecimal wcfRate, boolean sdlApplies, BigDecimal sdlRate, boolean nhifApplies, BigDecimal nhifEmployeeRate,
                        BigDecimal nhifEmployerRate, BigDecimal heslbRate) {
    }

    /** A standing allowance or deduction; {@code balance} is what is still owed on a loan (null for a standing deduction). */
    public record Item(Long id, String kind, String name, BigDecimal amount, boolean taxable, BigDecimal balance) {
    }

    public record Line(String kind, String name, BigDecimal amount, Long itemId) {
    }

    public record Result(BigDecimal basic, BigDecimal taxableAllowances, BigDecimal otherAllowances, BigDecimal gross, BigDecimal pensionEmployee,
                         BigDecimal taxableIncome, BigDecimal paye, BigDecimal nhifEmployee, BigDecimal heslb, BigDecimal otherDeductions, BigDecimal net,
                         BigDecimal pensionEmployer, BigDecimal nhifEmployer, BigDecimal wcf, BigDecimal sdl, List<Line> lines) {

        public BigDecimal totalDeductions() {
            return pensionEmployee.add(paye).add(nhifEmployee).add(heslb).add(otherDeductions);
        }

        public BigDecimal employerCost() {
            return pensionEmployer.add(nhifEmployer).add(wcf).add(sdl);
        }
    }

    private PayCalc() {
    }

    public static Result compute(Rates r, BigDecimal basicSalary, boolean heslbLoan, List<Item> items) {
        BigDecimal basic = whole(basicSalary);
        BigDecimal taxableAllowances = BigDecimal.ZERO, otherAllowances = BigDecimal.ZERO, otherDeductions = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        for (Item i : items) {
            if (!"ALLOWANCE".equals(i.kind())) continue;
            BigDecimal a = whole(i.amount());
            if (i.taxable()) taxableAllowances = taxableAllowances.add(a);
            else otherAllowances = otherAllowances.add(a);
            lines.add(new Line("ALLOWANCE", i.name(), a, i.id()));
        }
        BigDecimal gross = basic.add(taxableAllowances).add(otherAllowances);
        BigDecimal pensionBase = r.pensionOnBasic() ? basic : gross;
        BigDecimal pensionEmployee = pct(pensionBase, r.pensionEmployeeRate());
        BigDecimal taxableIncome = basic.add(taxableAllowances).subtract(pensionEmployee).max(BigDecimal.ZERO);
        BigDecimal paye = paye(taxableIncome, r.payeBands());
        BigDecimal nhifEmployee = r.nhifApplies() ? pct(basic, r.nhifEmployeeRate()) : BigDecimal.ZERO;
        BigDecimal heslb = heslbLoan ? pct(basic, r.heslbRate()) : BigDecimal.ZERO;
        for (Item i : items) {
            if (!"DEDUCTION".equals(i.kind())) continue;
            BigDecimal d = whole(i.amount());
            if (i.balance() != null) d = d.min(whole(i.balance()));
            if (d.signum() <= 0) continue;
            otherDeductions = otherDeductions.add(d);
            lines.add(new Line("DEDUCTION", i.name(), d, i.id()));
        }
        BigDecimal net = gross.subtract(pensionEmployee).subtract(paye).subtract(nhifEmployee).subtract(heslb).subtract(otherDeductions);
        return new Result(basic, taxableAllowances, otherAllowances, gross, pensionEmployee, taxableIncome, paye, nhifEmployee, heslb, otherDeductions, net,
                pct(pensionBase, r.pensionEmployerRate()), r.nhifApplies() ? pct(basic, r.nhifEmployerRate()) : BigDecimal.ZERO, pct(gross, r.wcfRate()),
                r.sdlApplies() ? pct(gross, r.sdlRate()) : BigDecimal.ZERO, lines);
    }

    /** PAYE by bands: each band's rate applies to the part of the income above its start and below the next band's start. */
    public static BigDecimal paye(BigDecimal taxable, List<Band> bands) {
        List<Band> sorted = bands.stream().sorted(Comparator.comparing(Band::from)).toList();
        BigDecimal tax = BigDecimal.ZERO;
        for (int i = 0; i < sorted.size(); i++) {
            BigDecimal from = sorted.get(i).from();
            if (taxable.compareTo(from) <= 0) break;
            BigDecimal to = i + 1 < sorted.size() ? sorted.get(i + 1).from().min(taxable) : taxable;
            tax = tax.add(to.subtract(from).multiply(sorted.get(i).rate()).divide(HUNDRED, 4, RoundingMode.HALF_UP));
        }
        return whole(tax);
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal rate) {
        return whole(base.multiply(rate).divide(HUNDRED, 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal whole(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP);
    }
}
