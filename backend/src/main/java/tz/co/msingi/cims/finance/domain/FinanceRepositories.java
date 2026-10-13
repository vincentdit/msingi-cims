package tz.co.msingi.cims.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class FinanceRepositories {

    private FinanceRepositories() {
    }

    public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
        List<Invoice> findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType type, Long payerId);

        List<Invoice> findByPayerTypeAndPayerIdAndPurposeAndStatusNotIn(Invoice.PayerType type, Long payerId, String purpose,
                                                                        java.util.Collection<Invoice.Status> excluded);

        /** Annual invoices with a balance (debtors), with lines. */
        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"lines"})
        @org.springframework.data.jpa.repository.Query("""
                select i from Invoice i where i.purpose = :purpose and i.status in ('ISSUED', 'PARTIALLY_PAID')
                  and (:academicYearId is null or i.academicYearId = :academicYearId)""")
        List<Invoice> openByPurpose(String purpose, Long academicYearId);

        /** Outstanding balance per student (all live invoices). */
        @org.springframework.data.jpa.repository.Query("""
                select i.payerId, sum(i.totalAmount - i.paidAmount) from Invoice i
                where i.payerType = tz.co.msingi.cims.finance.domain.Invoice.PayerType.STUDENT
                  and i.status not in (tz.co.msingi.cims.finance.domain.Invoice.Status.CANCELLED, tz.co.msingi.cims.finance.domain.Invoice.Status.CREDITED)
                group by i.payerId""")
        List<Object[]> studentBalances();

        /** What sponsors have been invoiced for a student invoice and not yet paid (counts towards the student's clearance). */
        @org.springframework.data.jpa.repository.Query("""
                select coalesce(sum(l.amount - l.paidAmount), 0) from InvoiceLine l
                where l.beneficiaryInvoiceId = :invoiceId
                  and l.invoice.status not in (tz.co.msingi.cims.finance.domain.Invoice.Status.CANCELLED, tz.co.msingi.cims.finance.domain.Invoice.Status.CREDITED)""")
        java.math.BigDecimal sponsorPending(@org.springframework.data.repository.query.Param("invoiceId") Long invoiceId);

        /** Everything sponsors have been invoiced for a student invoice (paid or not). */
        @org.springframework.data.jpa.repository.Query("""
                select coalesce(sum(l.amount), 0) from InvoiceLine l
                where l.beneficiaryInvoiceId = :invoiceId
                  and l.invoice.status not in (tz.co.msingi.cims.finance.domain.Invoice.Status.CANCELLED, tz.co.msingi.cims.finance.domain.Invoice.Status.CREDITED)""")
        java.math.BigDecimal sponsorInvoiced(@org.springframework.data.repository.query.Param("invoiceId") Long invoiceId);
    }

    public interface ControlNumberRepository extends JpaRepository<ControlNumber, Long> {
        Optional<ControlNumber> findByBillId(String billId);

        Optional<ControlNumber> findByControlNo(String controlNo);

        List<ControlNumber> findByInvoiceIdOrderByIdDesc(Long invoiceId);
    }

    public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {
        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoiceLine", "payment"})
        List<PaymentAllocation> findByPaymentIdIn(java.util.Collection<Long> paymentIds);
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        boolean existsByGepgReceiptNo(String gepgReceiptNo);

        Optional<Payment> findByGepgReceiptNo(String gepgReceiptNo);

        Optional<Payment> findByReceiptNo(String receiptNo);

        List<Payment> findByInvoiceIdOrderByPaidAtAsc(Long invoiceId);

        List<Payment> findByInvoiceIdInOrderByPaidAtAsc(java.util.Collection<Long> invoiceIds);

        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoice"})
        List<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(java.time.OffsetDateTime from, java.time.OffsetDateTime to);
    }
}
