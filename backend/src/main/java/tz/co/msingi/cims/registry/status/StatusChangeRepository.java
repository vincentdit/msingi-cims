package tz.co.msingi.cims.registry.status;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StatusChangeRepository extends JpaRepository<StatusChange, Long> {

    @EntityGraph(attributePaths = {"student", "student.programme"})
    List<StatusChange> findByStudentIdOrderByRequestedAtDesc(Long studentId);

    @EntityGraph(attributePaths = {"student", "student.programme"})
    List<StatusChange> findByStateInOrderByRequestedAtAsc(Collection<StatusChange.State> states);

    @EntityGraph(attributePaths = {"student", "student.programme"})
    List<StatusChange> findTop200ByOrderByRequestedAtDesc();

    @EntityGraph(attributePaths = {"student", "student.programme"})
    List<StatusChange> findByStateAndEffectiveDateLessThanEqual(StatusChange.State state, LocalDate date);

    boolean existsByStudentIdAndStateIn(Long studentId, Collection<StatusChange.State> states);
}
