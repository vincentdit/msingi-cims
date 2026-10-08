package tz.co.msingi.cims.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class RegistryRepositories {

    private RegistryRepositories() {
    }

    public interface StudentRepository extends JpaRepository<Student, Long> {
        boolean existsByRegistrationNo(String registrationNo);

        Optional<Student> findByApplicationId(Long applicationId);

        List<Student> findByApplicationIdIn(Collection<Long> applicationIds);

        @EntityGraph(attributePaths = {"programme", "entryAcademicYear", "intake"})
        @Query("""
                select s from Student s
                where (:programmeId is null or s.programme.id = :programmeId)
                  and (:status is null or s.status = :status)
                  and (:q is null or lower(s.firstName) like lower(concat('%', cast(:q as string), '%'))
                       or lower(s.surname) like lower(concat('%', cast(:q as string), '%'))
                       or lower(s.registrationNo) like lower(concat('%', cast(:q as string), '%'))
                       or s.phone like concat('%', cast(:q as string), '%'))""")
        Page<Student> search(Long programmeId, Student.Status status, String q, Pageable pageable);

        @EntityGraph(attributePaths = {"programme", "entryAcademicYear", "intake"})
        List<Student> findByProgrammeIdAndStatusInOrderBySurnameAscFirstNameAsc(Long programmeId, Collection<Student.Status> statuses);

        @EntityGraph(attributePaths = {"programme", "entryAcademicYear"})
        List<Student> findByEntryAcademicYearIdAndStatusIn(Long academicYearId, Collection<Student.Status> statuses);

        Optional<Student> findByApplicantId(Long applicantId);

        Optional<Student> findByUserId(Long userId);

        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "programme")
        List<Student> findByStatusIn(Collection<Student.Status> statuses);

        @Query("select s.status, count(s) from Student s group by s.status")
        List<Object[]> countByStatus();
    }

    public interface FirstRegistrationRepository extends JpaRepository<FirstRegistration, Long> {
        Optional<FirstRegistration> findByStudentId(Long studentId);
    }
}
