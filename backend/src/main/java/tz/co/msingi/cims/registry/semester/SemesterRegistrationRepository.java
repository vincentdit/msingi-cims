package tz.co.msingi.cims.registry.semester;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SemesterRegistrationRepository extends JpaRepository<SemesterRegistration, Long> {

    Optional<SemesterRegistration> findByStudentIdAndSemesterId(Long studentId, Long semesterId);

    @EntityGraph(attributePaths = {"student", "student.programme", "semester"})
    List<SemesterRegistration> findBySemesterIdOrderBySubmittedAtAsc(Long semesterId);

    @EntityGraph(attributePaths = {"semester", "semester.academicYear"})
    List<SemesterRegistration> findByStudentIdOrderBySubmittedAtDesc(Long studentId);

    @Query("select r.status, count(r) from SemesterRegistration r where r.semester.id = :semesterId group by r.status")
    List<Object[]> countByStatus(Long semesterId);
}
