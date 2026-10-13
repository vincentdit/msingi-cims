package tz.co.msingi.cims.finance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.ControlNumberRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentAllocationRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.InvoiceLine;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.domain.PaymentAllocation;
import tz.co.msingi.cims.finance.domain.PaymentAllocator;
import tz.co.msingi.cims.finance.gepg.GepgGateway;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.List;
import java.util.Map;

/** Invoices, GePG control numbers and payment posting (BP-06). */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final InvoiceRepository invoices;
    private final ControlNumberRepository controlNumbers;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final GepgGateway gepg;
    private final GepgProperties gepgProps;
    private final NumberSequenceService sequences;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final Messaging messaging;

    public record NewInvoice(Invoice.PayerType payerType, Long payerId, String payerName, String payerPhone, String payerEmail,
                             String purpose, Long referenceId, String description, List<Line> lines,
                             Long academicYearId, Long feeStructureId) {
        public NewInvoice(Invoice.PayerType payerType, Long payerId, String payerName, String payerPhone, String payerEmail,
                          String purpose, Long referenceId, String description, List<Line> lines) {
            this(payerType, payerId, payerName, payerPhone, payerEmail, purpose, referenceId, description, lines, null, null);
        }

        public record Line(String feeItemCode, String description, BigDecimal amount, Long feeItemId, short priority, short instalment) {
            public Line(String feeItemCode, String description, BigDecimal amount) {
                this(feeItemCode, description, amount, null, (short) 100, (short) 1);
            }
        }
    }

    @Transactional
    public Invoice createInvoice(NewInvoice req) {
        TenantContext.TenantInfo tenant = TenantContext.require();
        Invoice inv = new Invoice();
        inv.setInvoiceNo(NumberSequenceService.format(tenant.code() + "/INV/" + Year.now().getValue() + "/",
                sequences.next("INVOICE-" + Year.now().getValue()), 6));
        inv.setPayerType(req.payerType());
        inv.setPayerId(req.payerId());
        inv.setPayerName(req.payerName());
        inv.setPayerPhone(req.payerPhone());
        inv.setPayerEmail(req.payerEmail());
        inv.setPurpose(req.purpose());
        inv.setReferenceId(req.referenceId());
        inv.setDescription(req.description());
        req.lines().forEach(l -> {
            InvoiceLine line = inv.addLine(l.feeItemCode(), l.description(), l.amount());
            line.setFeeItemId(l.feeItemId());
            line.setPriority(l.priority());
            line.setInstalment(l.instalment());
        });
        inv.setAcademicYearId(req.academicYearId());
        inv.setFeeStructureId(req.feeStructureId());
        inv.setDueDate(OffsetDateTime.now().plus(gepgProps.controlNumberValidity()).toLocalDate());
        Invoice saved = invoices.save(inv);
        audit.record("CREATE", "Invoice", saved.getId(), Map.of("invoiceNo", saved.getInvoiceNo(), "amount", saved.getTotalAmount()));
        return saved;
    }

    /** Returns the current usable control number for the invoice, requesting a new one from GePG if needed. */
    @Transactional
    public ControlNumber ensureControlNumber(Long invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        if (inv.getStatus() == Invoice.Status.PAID) {
            throw new BusinessRuleException("INVOICE_PAID", "This invoice is already paid");
        }
        List<ControlNumber> existing = controlNumbers.findByInvoiceIdOrderByIdDesc(invoiceId);
        for (ControlNumber cn : existing) {
            if (cn.isUsable()) {
                return cn;
            }
            if (cn.getStatus() == ControlNumber.Status.ACTIVE) {
                cn.setStatus(ControlNumber.Status.EXPIRED);
            }
        }
        ControlNumber cn = new ControlNumber();
        cn.setInvoice(inv);
        cn.setBillId(TenantContext.requireTenantId() + "-" + inv.getId() + "-" + (existing.size() + 1));
        cn.setExpiresAt(OffsetDateTime.now().plus(gepgProps.controlNumberValidity()));
        GepgGateway.ControlNumberResponse resp = gepg.submitBill(new GepgGateway.BillRequest(
                cn.getBillId(), inv.getPayerName(), inv.getPayerPhone(), inv.getPayerEmail(),
                inv.getDescription(), inv.balance(), inv.getCurrency(), cn.getExpiresAt()));
        if (resp.issued()) {
            cn.setControlNo(resp.controlNo());
            cn.setStatus(ControlNumber.Status.ACTIVE);
            cn.setIssuedAt(OffsetDateTime.now());
            if (inv.getPayerPhone() != null) {
                messaging.event("CONTROL_NUMBER", inv.getPayerPhone(), Map.of("controlNo", resp.controlNo(), "amount", inv.balance(),
                        "description", inv.getDescription()));
            }
        } else if (resp.errorMessage() != null) {
            cn.setStatus(ControlNumber.Status.FAILED);
        }
        return controlNumbers.save(cn);
    }

    public record PaymentNotification(String billId, String controlNo, String gepgReceiptNo, String pspReceiptNo,
                                      String pspName, String channel, BigDecimal amount, String payerPhone,
                                      OffsetDateTime paidAt) {
    }

    /**
     * Posts a GePG payment notification. Idempotent: a notification whose receipt number was
     * already processed is acknowledged without changes.
     */
    @Transactional
    public Payment recordPayment(PaymentNotification n) {
        if (payments.existsByGepgReceiptNo(n.gepgReceiptNo())) {
            log.info("Duplicate GePG notification {} ignored", n.gepgReceiptNo());
            return null;
        }
        ControlNumber cn = controlNumbers.findByBillId(n.billId())
                .or(() -> controlNumbers.findByControlNo(n.controlNo()))
                .orElseThrow(() -> new NotFoundException("Bill", n.billId()));
        Invoice inv = cn.getInvoice();
        if (n.amount() == null || n.amount().signum() <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Payment amount must be positive");
        }

        Payment p = new Payment();
        p.setInvoice(inv);
        p.setControlNo(cn.getControlNo());
        p.setGepgReceiptNo(n.gepgReceiptNo());
        p.setPspReceiptNo(n.pspReceiptNo());
        p.setPspName(n.pspName());
        p.setChannel(n.channel());
        p.setAmount(n.amount());
        p.setPayerPhone(n.payerPhone());
        p.setPaidAt(n.paidAt() == null ? OffsetDateTime.now() : n.paidAt());
        p.setStatus(Payment.Status.ALLOCATED);
        p.setReceiptNo(NumberSequenceService.format(TenantContext.require().code() + "/RCT/" + Year.now().getValue() + "/",
                sequences.next("RECEIPT-" + Year.now().getValue()), 6));

        boolean wasPaid = inv.isPaid();
        PaymentAllocator.Result allocation = inv.applyPayment(n.amount());
        p.setUnallocatedAmount(allocation.unallocated());
        payments.save(p);
        for (PaymentAllocator.Allocation a : allocation.allocations()) {
            PaymentAllocation pa = new PaymentAllocation();
            pa.setPayment(p);
            pa.setInvoiceLine(a.line());
            pa.setAmount(a.amount());
            allocations.save(pa);
        }
        boolean fullyPaid = !wasPaid && inv.isPaid();
        if (inv.isPaid()) {
            cn.setStatus(ControlNumber.Status.PAID);
        }
        if (allocation.unallocated().signum() > 0) {
            log.info("Payment {} exceeds the balance of invoice {} by {}: held as credit", n.gepgReceiptNo(), inv.getInvoiceNo(), allocation.unallocated());
        }
        audit.recordAs(null, "GePG", "PAYMENT_RECEIVED", "Invoice", inv.getId(),
                Map.of("receiptNo", p.getReceiptNo(), "gepgReceiptNo", n.gepgReceiptNo(), "amount", n.amount(),
                        "credit", allocation.unallocated()));
        events.publishEvent(new PaymentPostedEvent(inv.getId(), inv.getPayerType().name(), inv.getPayerId(), p.getId()));
        if (inv.getPayerPhone() != null) {
            messaging.event("PAYMENT_RECEIVED", inv.getPayerPhone(), Map.of("amount", n.amount(), "receiptNo", p.getReceiptNo()));
        }
        if (fullyPaid) {
            events.publishEvent(new InvoicePaidEvent(inv.getId(), inv.getPurpose(), inv.getReferenceId()));
        }
        return p;
    }

    /**
     * Posts money that did not come through GePG (BP-17): a bank remittance recorded by the Accountant (cash), or an internal posting
     * that is not cash, such as a sponsor's share moved onto the student's invoice or an approved waiver. Allocated to the invoice
     * lines like any payment; anything beyond the balance is held as credit.
     */
    @Transactional
    public Payment postDirect(Invoice inv, BigDecimal amount, Payment.Source source, String channel, String reference, String documentNo,
                              OffsetDateTime paidAt) {
        if (source == Payment.Source.GEPG) throw new IllegalArgumentException("GePG payments come through recordPayment");
        if (amount == null || amount.signum() <= 0) throw new BusinessRuleException("INVALID_AMOUNT", "The amount must be positive");
        String code = TenantContext.require().code();
        Payment p = new Payment();
        p.setInvoice(inv);
        p.setControlNo(source.name());
        p.setGepgReceiptNo(source.name() + "-" + NumberSequenceService.format("", sequences.next("DIRECT-PAYMENT"), 8));
        p.setPspReceiptNo(reference == null ? null : reference.length() > 60 ? reference.substring(0, 60) : reference);
        p.setPspName(source == Payment.Source.BANK ? "Bank" : null);
        p.setChannel(channel);
        p.setAmount(amount);
        p.setPaidAt(paidAt == null ? OffsetDateTime.now() : paidAt);
        p.setStatus(Payment.Status.ALLOCATED);
        p.setSource(source);
        p.setRecordedByName(tz.co.msingi.cims.platform.security.CurrentUser.id().isPresent() ? tz.co.msingi.cims.platform.security.CurrentUser.name() : "CIMS");
        p.setReceiptNo(documentNo != null ? documentNo : NumberSequenceService.format(code + "/RCT/" + Year.now().getValue() + "/",
                sequences.next("RECEIPT-" + Year.now().getValue()), 6));
        boolean wasPaid = inv.isPaid();
        PaymentAllocator.Result allocation = inv.applyPayment(amount);
        p.setUnallocatedAmount(allocation.unallocated());
        payments.save(p);
        for (PaymentAllocator.Allocation a : allocation.allocations()) {
            PaymentAllocation pa = new PaymentAllocation();
            pa.setPayment(p);
            pa.setInvoiceLine(a.line());
            pa.setAmount(a.amount());
            allocations.save(pa);
        }
        audit.record("PAYMENT_POSTED", "Invoice", inv.getId(), Map.of("receiptNo", p.getReceiptNo(), "source", source.name(), "channel", channel,
                "amount", amount, "credit", allocation.unallocated()));
        events.publishEvent(new PaymentPostedEvent(inv.getId(), inv.getPayerType().name(), inv.getPayerId(), p.getId()));
        if (source == Payment.Source.BANK && inv.getPayerPhone() != null) {
            messaging.event("PAYMENT_RECEIVED", inv.getPayerPhone(), Map.of("amount", amount, "receiptNo", p.getReceiptNo()));
        }
        if (!wasPaid && inv.isPaid()) events.publishEvent(new InvoicePaidEvent(inv.getId(), inv.getPurpose(), inv.getReferenceId()));
        return p;
    }

    @Transactional(readOnly = true)
    public Invoice get(Long invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
    }

    /** Latest control number for the invoice (active or paid), or null if none was issued. */
    @Transactional(readOnly = true)
    public ControlNumber currentControlNumber(Long invoiceId) {
        return controlNumbers.findByInvoiceIdOrderByIdDesc(invoiceId).stream()
                .filter(cn -> cn.getStatus() == ControlNumber.Status.ACTIVE || cn.getStatus() == ControlNumber.Status.PAID)
                .findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Payment> paymentsFor(Long invoiceId) {
        return payments.findByInvoiceIdOrderByPaidAtAsc(invoiceId);
    }
}
