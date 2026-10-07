package tz.co.msingi.cims.finance.reports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentAllocationRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.domain.PaymentAllocation;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collections and debtor ageing for the Bursar's office (FR-FIN-12). */
@Service
@RequiredArgsConstructor
public class FinanceReportService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final List<String> BUCKETS = List.of("NOT_DUE", "1-30", "31-60", "61-90", "OVER_90");

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final InvoiceRepository invoices;
    private final StudentRepository students;
    private final AuditService audit;

    public record Amount(String key, String label, BigDecimal amount, long count) {
    }

    public record PaymentRow(String receiptNo, String paidAt, String payerName, String invoiceNo, String purpose, BigDecimal amount,
                             BigDecimal credit, String channel, String pspName) {
    }

    public record Collections(LocalDate from, LocalDate to, BigDecimal total, BigDecimal credit, long payments,
                              List<Amount> byFeeItem, List<Amount> byDay, List<Amount> byChannel, List<PaymentRow> rows) {
    }

    public record DebtorRow(Long invoiceId, String invoiceNo, String payerType, String payerName, String registrationNo, String phone,
                            String description, BigDecimal total, BigDecimal paid, BigDecimal balance, LocalDate dueDate,
                            long daysOverdue, String bucket) {
    }

    public record Debtors(LocalDate asAt, BigDecimal totalBalance, long count, List<Amount> byBucket, List<DebtorRow> rows) {
    }

    @Transactional(readOnly = true)
    public Collections collections(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new BusinessRuleException("INVALID_RANGE", "The end date is before the start date");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw new BusinessRuleException("RANGE_TOO_LONG", "Choose a period of at most one year");
        List<Payment> list = payments.findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                from.atStartOfDay(ZONE).toOffsetDateTime(), to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        Map<String, BigDecimal> byItem = new TreeMap<>();
        Map<String, String> itemLabel = new LinkedHashMap<>();
        Map<String, Long> itemCount = new TreeMap<>();
        if (!list.isEmpty()) {
            for (PaymentAllocation a : allocations.findByPaymentIdIn(list.stream().map(Payment::getId).toList())) {
                String code = a.getInvoiceLine().getFeeItemCode();
                byItem.merge(code, a.getAmount(), BigDecimal::add);
                itemCount.merge(code, 1L, Long::sum);
                itemLabel.putIfAbsent(code, a.getInvoiceLine().getDescription());
            }
        }
        BigDecimal credit = list.stream().map(Payment::getUnallocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (credit.signum() > 0) {
            byItem.put("~CREDIT", credit);
            itemLabel.put("~CREDIT", "Unallocated (held as credit)");
            itemCount.put("~CREDIT", list.stream().filter(p -> p.getUnallocatedAmount().signum() > 0).count());
        }
        Map<String, List<Payment>> perDay = list.stream().collect(Collectors.groupingBy(
                p -> p.getPaidAt().atZoneSameInstant(ZONE).toLocalDate().toString(), TreeMap::new, Collectors.toList()));
        Map<String, List<Payment>> perChannel = list.stream().collect(Collectors.groupingBy(
                p -> p.getChannel() == null ? "OTHER" : p.getChannel(), TreeMap::new, Collectors.toList()));
        return new Collections(from, to, sum(list), credit, list.size(),
                byItem.entrySet().stream().map(e -> new Amount(e.getKey().equals("~CREDIT") ? "CREDIT" : e.getKey(),
                        itemLabel.get(e.getKey()), e.getValue(), itemCount.get(e.getKey()))).toList(),
                perDay.entrySet().stream().map(e -> new Amount(e.getKey(), e.getKey(), sum(e.getValue()), e.getValue().size())).toList(),
                perChannel.entrySet().stream().map(e -> new Amount(e.getKey(), e.getKey(), sum(e.getValue()), e.getValue().size())).toList(),
                list.stream().map(p -> new PaymentRow(p.getReceiptNo(), p.getPaidAt().atZoneSameInstant(ZONE).toLocalDateTime().toString(),
                        p.getInvoice().getPayerName(), p.getInvoice().getInvoiceNo(), p.getInvoice().getPurpose(), p.getAmount(),
                        p.getUnallocatedAmount(), p.getChannel(), p.getPspName())).toList());
    }

    /** Open annual fee invoices with what is still owed, aged by due date. */
    @Transactional(readOnly = true)
    public Debtors debtors(Long academicYearId) {
        LocalDate today = LocalDate.now(ZONE);
        List<Invoice> open = invoices.openByPurpose(AnnualBillingService.PURPOSE, academicYearId);
        Map<Long, Student> studentById = students.findAllById(open.stream().filter(i -> i.getPayerType() == Invoice.PayerType.STUDENT)
                .map(Invoice::getPayerId).toList()).stream().collect(Collectors.toMap(Student::getId, Function.identity()));
        List<DebtorRow> rows = new ArrayList<>();
        for (Invoice i : open) {
            long overdue = i.getDueDate() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(i.getDueDate(), today));
            Student s = i.getPayerType() == Invoice.PayerType.STUDENT ? studentById.get(i.getPayerId()) : null;
            rows.add(new DebtorRow(i.getId(), i.getInvoiceNo(), i.getPayerType().name(), i.getPayerName(),
                    s == null ? null : s.getRegistrationNo(), i.getPayerPhone(), i.getDescription(), i.getTotalAmount(), i.getPaidAmount(),
                    i.balance(), i.getDueDate(), overdue, bucket(overdue)));
        }
        rows.sort((a, b) -> b.balance().compareTo(a.balance()));
        Map<String, List<DebtorRow>> grouped = rows.stream().collect(Collectors.groupingBy(DebtorRow::bucket));
        List<Amount> byBucket = BUCKETS.stream().map(b -> {
            List<DebtorRow> r = grouped.getOrDefault(b, List.of());
            return new Amount(b, label(b), r.stream().map(DebtorRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add), r.size());
        }).toList();
        return new Debtors(today, rows.stream().map(DebtorRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add), rows.size(), byBucket, rows);
    }

    @Transactional
    public String collectionsCsv(LocalDate from, LocalDate to) {
        Collections c = collections(from, to);
        StringBuilder sb = new StringBuilder(Csv.line("Receipt No", "Paid at", "Payer", "Invoice", "Purpose", "Amount", "Credit", "Channel", "PSP"));
        c.rows().forEach(r -> sb.append(Csv.line(r.receiptNo(), r.paidAt(), r.payerName(), r.invoiceNo(), r.purpose(), r.amount(),
                r.credit(), r.channel(), r.pspName())));
        audit.record("COLLECTIONS_EXPORTED", "Report", null, Map.of("from", from.toString(), "to", to.toString(), "payments", c.payments()));
        return sb.toString();
    }

    @Transactional
    public String debtorsCsv(Long academicYearId) {
        Debtors d = debtors(academicYearId);
        StringBuilder sb = new StringBuilder(Csv.line("Invoice", "Payer", "Registration No", "Phone", "Description", "Total", "Paid",
                "Balance", "Due date", "Days overdue", "Ageing"));
        d.rows().forEach(r -> sb.append(Csv.line(r.invoiceNo(), r.payerName(), r.registrationNo(), r.phone(), r.description(), r.total(),
                r.paid(), r.balance(), r.dueDate(), r.daysOverdue(), label(r.bucket()))));
        audit.record("DEBTORS_EXPORTED", "Report", null, Map.of("debtors", d.count()));
        return sb.toString();
    }

    static String bucket(long daysOverdue) {
        if (daysOverdue <= 0) return "NOT_DUE";
        if (daysOverdue <= 30) return "1-30";
        if (daysOverdue <= 60) return "31-60";
        if (daysOverdue <= 90) return "61-90";
        return "OVER_90";
    }

    static String label(String bucket) {
        return switch (bucket) {
            case "NOT_DUE" -> "Not yet due";
            case "OVER_90" -> "Over 90 days";
            default -> bucket + " days";
        };
    }

    private static BigDecimal sum(List<Payment> list) {
        return list.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
