package tz.co.msingi.cims.console.billing;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.console.billing.BillingModels.*;
import tz.co.msingi.cims.finance.accounts.ReceiptService;
import tz.co.msingi.cims.finance.collection.PaymentProvider;
import tz.co.msingi.cims.platform.tenancy.Tenant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static tz.co.msingi.cims.common.document.HtmlTemplate.escape;
import static tz.co.msingi.cims.console.billing.MsingiBillingService.DAY;
import static tz.co.msingi.cims.console.billing.MsingiBillingService.TZ;
import static tz.co.msingi.cims.console.billing.MsingiBillingService.tzs;

/** Msingi's invoices, receipts and statements of account as PDF. */
@Component
public class BillingDocuments {

    public record Pdf(String fileName, byte[] content) {
        public ResponseEntity<byte[]> download() {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                    .contentType(MediaType.APPLICATION_PDF).body(content);
        }
    }

    private static final HtmlTemplate INVOICE = HtmlTemplate.classpath("templates/msingi-invoice.html");
    private static final HtmlTemplate RECEIPT = HtmlTemplate.classpath("templates/msingi-receipt.html");
    private static final HtmlTemplate STATEMENT = HtmlTemplate.classpath("templates/msingi-statement.html");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK);

    public Pdf invoice(InvoiceView v, Settings s, Tenant t, Contract c) {
        InvoiceRow r = v.invoice();
        Map<String, String> val = company(s);
        boolean draft = "DRAFT".equals(r.status());
        val.put("title", draft ? "DRAFT INVOICE" : "CANCELLED".equals(r.status()) ? "INVOICE (CANCELLED)" : "TAX INVOICE");
        val.put("invoiceNo", r.invoiceNo() == null ? "Draft" : r.invoiceNo());
        val.put("issueDate", r.issueDate() == null ? "—" : DAY.format(r.issueDate()));
        val.put("dueDate", r.dueDate() == null ? "—" : DAY.format(r.dueDate()));
        val.put("period", r.periodFrom() == null ? "—" : DAY.format(r.periodFrom()) + " – " + DAY.format(r.periodTo()));
        val.put("reference", r.paymentReference() == null ? "Given when issued" : r.paymentReference());
        val.put("billTo", t.getName());
        val.put("billToLine", Stream.of(c == null ? null : c.billingContactName(), c != null && c.billingEmail() != null ? c.billingEmail() : t.getContactEmail(),
                t.getContactPhone()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" · ")));
        val.put("contractRef", c == null || c.reference() == null ? "—" : c.reference());
        val.put("subtotal", tzs(v.subtotal()));
        val.put("vatLabel", "VAT " + v.vatPercent().stripTrailingZeros().toPlainString() + "%");
        val.put("vat", tzs(v.vatAmount()));
        val.put("total", tzs(r.total()));
        val.put("paid", tzs(r.paidAmount()));
        val.put("balance", tzs(r.balance()));
        val.put("words", ReceiptService.AmountInWords.tzs(r.total()));
        Map<String, String> mk = new HashMap<>();
        StringBuilder rows = new StringBuilder();
        for (Line l : v.lines()) {
            rows.append("<tr><td>").append(escape(l.description())).append("</td><td class=\"r\">").append(escape(qty(l.quantity())))
                    .append("</td><td class=\"r\">").append(escape(tzs(l.unitPrice()))).append("</td><td class=\"r\">").append(escape(tzs(l.amount()))).append("</td></tr>");
        }
        if (v.lines().isEmpty()) rows.append("<tr><td colspan=\"4\" class=\"muted\">No charges yet</td></tr>");
        mk.put("lines", rows.toString());
        mk.put("notes", v.notes() == null ? "" : "<p class=\"note\">" + escape(v.notes()) + "</p>");
        mk.put("payTo", payTo(s));
        mk.put("paidRows", r.paidAmount().signum() > 0 ? "<tr><td colspan=\"3\" class=\"r\">Paid</td><td class=\"r\">" + escape(tzs(r.paidAmount()))
                + "</td></tr><tr class=\"strong\"><td colspan=\"3\" class=\"r\">Balance to pay</td><td class=\"r\">TZS " + escape(tzs(r.balance())) + "</td></tr>" : "");
        mk.put("stamp", draft ? "<div class=\"stamp\">DRAFT — not yet issued</div>" : "PAID".equals(r.status()) ? "<div class=\"stamp paid\">PAID</div>"
                : "CANCELLED".equals(r.status()) ? "<div class=\"stamp\">CANCELLED</div>" : "");
        String name = "invoice-" + (r.invoiceNo() == null ? "draft-" + r.id() : r.invoiceNo()) + ".pdf";
        return new Pdf(name, PdfRenderer.render(INVOICE.render(val, mk), "invoice " + val.get("invoiceNo")));
    }

    public Pdf receipt(PaymentRow p, Settings s, Tenant t) {
        Map<String, String> val = company(s);
        val.put("receiptNo", p.receiptNo());
        val.put("paidAt", WHEN.format(p.paidAt().atZoneSameInstant(TZ)));
        val.put("from", t.getName());
        PaymentProvider pp = MsingiBillingService.provider(p.provider());
        val.put("method", MsingiBillingService.methodLabel(p.method()) + (p.provider() == null ? "" : " · " + (pp == null ? p.provider() : pp.label()))
                + (p.reference() == null ? "" : " · ref " + p.reference()));
        val.put("amount", "TZS " + tzs(p.amount()));
        val.put("words", ReceiptService.AmountInWords.tzs(p.amount()));
        val.put("invoices", p.invoices() == null || p.invoices().isBlank() ? "Held as credit" : p.invoices());
        Map<String, String> mk = new HashMap<>();
        mk.put("credit", p.unallocated().signum() > 0 ? "<p>TZS " + escape(tzs(p.unallocated())) + " is held as credit for the next invoice.</p>" : "");
        mk.put("stamp", "REVERSED".equals(p.status()) ? "<div class=\"stamp\">REVERSED</div>" : "");
        return new Pdf("receipt-" + p.receiptNo() + ".pdf", PdfRenderer.render(RECEIPT.render(val, mk), "receipt " + p.receiptNo()));
    }

    public Pdf statement(Statement st, Settings s) {
        Map<String, String> val = company(s);
        val.put("institution", st.institutionName());
        val.put("date", DAY.format(LocalDate.now(TZ)));
        val.put("invoiced", tzs(st.invoiced()));
        val.put("paid", tzs(st.paid()));
        val.put("balance", tzs(st.balance()));
        Aging a = st.aging();
        val.put("current", tzs(a.current()));
        val.put("a1", tzs(a.days1to30()));
        val.put("a2", tzs(a.days31to60()));
        val.put("a3", tzs(a.days61to90()));
        val.put("a4", tzs(a.over90()));
        Map<String, String> mk = new HashMap<>();
        StringBuilder rows = new StringBuilder();
        for (Entry e : st.entries()) {
            rows.append("<tr><td>").append(escape(DAY.format(e.date()))).append("</td><td>").append(escape(e.reference())).append("</td><td>")
                    .append(escape(e.description())).append("</td><td class=\"r\">").append(e.debit().signum() == 0 ? "" : escape(tzs(e.debit())))
                    .append("</td><td class=\"r\">").append(e.credit().signum() == 0 ? "" : escape(tzs(e.credit()))).append("</td><td class=\"r\">")
                    .append(escape(tzs(e.balance()))).append("</td></tr>");
        }
        if (st.entries().isEmpty()) rows.append("<tr><td colspan=\"6\" class=\"muted\">No invoices or payments yet</td></tr>");
        mk.put("rows", rows.toString());
        mk.put("credit", st.credit().signum() > 0 ? "<p>Credit held for your next invoice: <strong>TZS " + escape(tzs(st.credit())) + "</strong></p>" : "");
        mk.put("payTo", payTo(s));
        return new Pdf("statement-" + st.institutionCode() + ".pdf", PdfRenderer.render(STATEMENT.render(val, mk), "statement " + st.institutionCode()));
    }

    private static Map<String, String> company(Settings s) {
        Map<String, String> v = new HashMap<>();
        v.put("company", s.companyName());
        v.put("companyLine", Stream.of(s.address(), s.phone(), s.email(), s.website()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" · ")));
        v.put("taxLine", Stream.of(s.tin() == null ? null : "TIN " + s.tin(), s.vrn() == null ? null : "VRN " + s.vrn())
                .filter(x -> x != null).collect(Collectors.joining(" · ")));
        v.put("footer", s.invoiceNote() == null ? "Thank you for using CIMS." : s.invoiceNote());
        return v;
    }

    private static String payTo(Settings s) {
        if (s.channels().isEmpty()) return "";
        StringBuilder b = new StringBuilder("<h3>How to pay</h3><table class=\"pay\">");
        for (Channel c : s.channels()) {
            PaymentProvider p = MsingiBillingService.provider(c.provider());
            b.append("<tr><td>").append(escape(p == null ? c.provider() : p.label())).append("</td><td><strong>").append(escape(c.accountNumber()))
                    .append("</strong></td><td>").append(escape(Stream.of(c.accountName(), c.branch()).filter(x -> x != null).collect(Collectors.joining(", "))))
                    .append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", q.longValue()) : q.stripTrailingZeros().toPlainString();
    }
}
