package tz.co.msingi.cims.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class FinanceRepositories {

    private FinanceRepositories() {
    }

    public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
        List<Invoice> findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType type, Long payerId);
    }

    public interface ControlNumberRepository extends JpaRepository<ControlNumber, Long> {
        Optional<ControlNumber> findByBillId(String billId);

        Optional<ControlNumber> findByControlNo(String controlNo);

        List<ControlNumber> findByInvoiceIdOrderByIdDesc(Long invoiceId);
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        boolean existsByGepgReceiptNo(String gepgReceiptNo);

        List<Payment> findByInvoiceIdOrderByPaidAtAsc(Long invoiceId);
    }
}
