package tz.co.msingi.cims.finance.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class ReconciliationRepositories {

    private ReconciliationRepositories() {
    }

    public interface GepgReconciliationRepository extends JpaRepository<GepgReconciliation, Long> {
        Optional<GepgReconciliation> findByReconDate(LocalDate date);

        List<GepgReconciliation> findByReconDateBetweenOrderByReconDateAsc(LocalDate from, LocalDate to);
    }

    public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, Long> {
        List<ReconciliationItem> findByReconciliationIdOrderByIdAsc(Long reconciliationId);

        long countByReconciliationIdAndStatus(Long reconciliationId, ReconciliationItem.Status status);

        @Modifying
        @Query("delete from ReconciliationItem i where i.reconciliation.id = :reconciliationId")
        void deleteByReconciliationId(Long reconciliationId);

        /** Payments GePG has reported in any file (so they are not "missing in GePG"). */
        @Query("""
                select distinct i.paymentId from ReconciliationItem i
                where i.paymentId in :paymentIds and i.kind in ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_IN_CIMS')""")
        List<Long> reportedByGepg(Collection<Long> paymentIds);

        List<ReconciliationItem> findByPaymentIdAndKindAndStatus(Long paymentId, ReconciliationItem.Kind kind, ReconciliationItem.Status status);
    }

    public interface ReconciliationSignoffRepository extends JpaRepository<ReconciliationSignoff, Long> {
        Optional<ReconciliationSignoff> findByMonth(LocalDate month);
    }
}
