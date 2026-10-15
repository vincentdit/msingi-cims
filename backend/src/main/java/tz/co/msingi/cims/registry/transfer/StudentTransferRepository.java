package tz.co.msingi.cims.registry.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StudentTransferRepository extends JpaRepository<StudentTransfer, Long> {

    boolean existsByStudentIdAndStateIn(Long studentId, Collection<StudentTransfer.State> states);

    List<StudentTransfer> findByStudentIdOrderByRequestedAtDesc(Long studentId);

    List<StudentTransfer> findByStateInOrderByRequestedAtAsc(Collection<StudentTransfer.State> states);

    List<StudentTransfer> findTop200ByOrderByRequestedAtDesc();
}
