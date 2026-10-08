package tz.co.msingi.cims.finance.accounts;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentAllocationRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.domain.PaymentAllocation;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Electronic receipts (FR-FIN-04) as PDF, for the payer's own payments. */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/receipt.html");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final AccountService accounts;
    private final StudentRepository students;
    private final TenantRepository tenants;

    /** The receipt for one of the signed-in applicant's or student's payments; anyone else's is "not found". */
    @Transactional(readOnly = true)
    public byte[] mine(String receiptNo) {
        Payment p = payments.findByReceiptNo(receiptNo).orElseThrow(() -> new NotFoundException("Receipt", receiptNo));
        accounts.requireMine(p.getInvoice().getId());
        return render(p);
    }

    public static ResponseEntity<byte[]> asDownload(String receiptNo, byte[] pdf) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("receipt-" + receiptNo.replaceAll("[^A-Za-z0-9-]", "-") + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    byte[] render(Payment p) {
        Invoice inv = p.getInvoice();
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        Map<String, String> v = new HashMap<>();
        v.put("institutionName", t.getName());
        v.put("institutionShortName", t.getShortName() == null || t.getShortName().isBlank()
                ? Stream.of(t.getName().split("\\s+")).filter(w -> !w.isEmpty()).limit(3).map(w -> w.substring(0, 1)).collect(Collectors.joining()).toUpperCase(Locale.ROOT)
                : t.getShortName().length() > 5 ? t.getShortName().substring(0, 5) : t.getShortName());
        v.put("contactLine", Stream.of(t.getContactPhone(), t.getContactEmail()).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" · ")));
        Object colour = t.getBranding().get("primaryColor");
        v.put("primaryColor", colour instanceof String c && HEX_COLOUR.matcher(c).matches() ? c : "#1F4E8C");
        v.put("receiptNo", p.getReceiptNo());
        v.put("paidAt", WHEN.format(p.getPaidAt().atZoneSameInstant(ZONE)));
        v.put("payerName", inv.getPayerName());
        v.put("invoiceNo", inv.getInvoiceNo());
        v.put("invoiceDescription", inv.getDescription());
        v.put("controlNo", nz(p.getControlNo()));
        v.put("paidThrough", Stream.of(p.getPspName(), p.getChannel(), p.getPspReceiptNo() == null ? null : "ref " + p.getPspReceiptNo())
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" · ")));
        v.put("gepgReceiptNo", nz(p.getGepgReceiptNo()));
        v.put("amount", "TZS " + AccountService.tzs(p.getAmount()));
        v.put("amountInWords", AmountInWords.tzs(p.getAmount()));
        v.put("invoiceBalance", "TZS " + AccountService.tzs(inv.balance()));

        Map<String, String> m = new HashMap<>();
        String regNo = inv.getPayerType() == Invoice.PayerType.STUDENT
                ? students.findById(inv.getPayerId()).map(Student::getRegistrationNo).orElse(null) : null;
        m.put("registrationRow", regNo == null ? "" : "<tr><th>Registration no.</th><td>" + HtmlTemplate.escape(regNo) + "</td></tr>");
        List<PaymentAllocation> alloc = allocations.findByPaymentIdIn(List.of(p.getId()));
        StringBuilder rows = new StringBuilder();
        for (PaymentAllocation a : alloc) {
            rows.append("<tr><td>").append(HtmlTemplate.escape(a.getInvoiceLine().getDescription()))
                    .append(a.getInvoiceLine().getInstalment() > 1 ? " (instalment " + a.getInvoiceLine().getInstalment() + ")" : "")
                    .append("</td><td class=\"r\">").append(HtmlTemplate.escape("TZS " + AccountService.tzs(a.getAmount()))).append("</td></tr>");
        }
        if (p.getUnallocatedAmount().signum() > 0) {
            rows.append("<tr><td>Held as credit on your account</td><td class=\"r\">")
                    .append(HtmlTemplate.escape("TZS " + AccountService.tzs(p.getUnallocatedAmount()))).append("</td></tr>");
        }
        m.put("allocationRows", rows.toString());
        return PdfRenderer.render(TEMPLATE.render(v, m), "receipt " + p.getReceiptNo());
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /** "Tanzanian shillings one hundred twenty thousand only". */
    static final class AmountInWords {
        private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

        private AmountInWords() {
        }

        static String tzs(BigDecimal amount) {
            long shillings = amount.setScale(0, RoundingMode.DOWN).longValueExact();
            int cents = amount.subtract(BigDecimal.valueOf(shillings)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
            String w = "Tanzanian shillings " + words(shillings);
            if (cents > 0) w += " and cents " + words(cents);
            return w + " only";
        }

        static String words(long n) {
            if (n < 20) return ONES[(int) n];
            if (n < 100) return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + ONES[(int) (n % 10)]);
            if (n < 1_000) return ONES[(int) (n / 100)] + " hundred" + (n % 100 == 0 ? "" : " " + words(n % 100));
            long[] scales = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
            String[] names = {"trillion", "billion", "million", "thousand"};
            for (int i = 0; i < scales.length; i++) {
                if (n >= scales[i]) {
                    return words(n / scales[i]) + " " + names[i] + (n % scales[i] == 0 ? "" : " " + words(n % scales[i]));
                }
            }
            throw new IllegalStateException();
        }
    }
}
