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

        List<Payment> findByInvoiceIdOrderByPaidAtAsc(Long invoiceId);

        List<Payment> findByInvoiceIdInOrderByPaidAtAsc(java.util.Collection<Long> invoiceIds);

        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"invoice"})
        List<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(java.time.OffsetDateTime from, java.time.OffsetDateTime to);
    }
}
