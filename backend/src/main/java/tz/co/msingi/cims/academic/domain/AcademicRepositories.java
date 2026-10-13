package tz.co.msingi.cims.academic.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Repositories for the academic structure. Row-level security limits every query to the current institution. */
public final class AcademicRepositories {

    private AcademicRepositories() {
    }

    public interface AcademicYearRepository extends JpaRepository<AcademicYear, Long> {
        List<AcademicYear> findAllByOrderByStartDateDesc();

        Optional<AcademicYear> findByCode(String code);

        @Modifying
        @Query("update AcademicYear y set y.current = false where y.current = true")
        void clearCurrent();
    }

    public interface IntakeRepository extends JpaRepository<Intake, Long> {
        List<Intake> findAllByOrderByStartMonthAsc();

        Optional<Intake> findByCode(String code);
    }

    public interface DepartmentRepository extends JpaRepository<Department, Long> {
        List<Department> findAllByOrderByNameAsc();

        Optional<Department> findByCode(String code);
    }

    public interface ProgrammeRepository extends JpaRepository<Programme, Long> {
        @EntityGraph(attributePaths = "department")
        List<Programme> findAllByOrderByAwardFamilyAscAwardLevelAscNameAsc();

        Optional<Programme> findByCode(String code);

        boolean existsByCode(String code);

        @org.springframework.data.jpa.repository.Query("select p.id from Programme p where p.department.id in :departmentIds")
        List<Long> idsInDepartments(java.util.Collection<Long> departmentIds);
    }
}
