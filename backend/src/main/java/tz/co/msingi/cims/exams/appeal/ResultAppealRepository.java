package tz.co.msingi.cims.exams.appeal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResultAppealRepository extends JpaRepository<ResultAppeal, Long> {

    List<ResultAppeal> findByStudentIdOrderByRequestedAtDesc(Long studentId);

    List<ResultAppeal> findByStatusInOrderByRequestedAtAsc(Collection<ResultAppeal.Status> statuses);

    List<ResultAppeal> findTop300ByOrderByRequestedAtDesc();

    List<ResultAppeal> findByExaminerUserIdOrderByRequestedAtDesc(Long examinerUserId);

    boolean existsByModuleResultIdAndStatusIn(Long moduleResultId, Collection<ResultAppeal.Status> statuses);

    Optional<ResultAppeal> findByAmendmentId(Long amendmentId);
}
