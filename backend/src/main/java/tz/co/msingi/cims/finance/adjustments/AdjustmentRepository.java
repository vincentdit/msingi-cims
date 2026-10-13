package tz.co.msingi.cims.finance.adjustments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AdjustmentRepository extends JpaRepository<FinanceAdjustment, Long> {
    List<FinanceAdjustment> findTop300ByOrderByRequestedAtDesc();

    List<FinanceAdjustment> findByStatusInOrderByRequestedAtAsc(Collection<FinanceAdjustment.Status> statuses);

    List<FinanceAdjustment> findByStudentIdAndStatusIn(Long studentId, Collection<FinanceAdjustment.Status> statuses);

    List<FinanceAdjustment> findByInvoiceIdAndStatusIn(Long invoiceId, Collection<FinanceAdjustment.Status> statuses);
}
