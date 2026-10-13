package tz.co.msingi.cims.exams.competence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class CompetenceRepositories {

    private CompetenceRepositories() {
    }

    public interface CompetenceUnitRepository extends JpaRepository<CompetenceUnit, Long> {
        List<CompetenceUnit> findBySemesterIdOrderByIdAsc(Long semesterId);

        Optional<CompetenceUnit> findBySemesterIdAndModuleId(Long semesterId, Long moduleId);

        @Query("select distinct u from CompetenceUnit u join u.assessors a where a.userId = :userId")
        List<CompetenceUnit> findForAssessor(@Param("userId") Long userId);
    }

    public interface CompetenceAssessmentRepository extends JpaRepository<CompetenceAssessment, Long> {
        @Query("select a from CompetenceAssessment a where a.task.unit.id = :unitId order by a.attempt")
        List<CompetenceAssessment> findByUnit(@Param("unitId") Long unitId);

        @Query("select a from CompetenceAssessment a join fetch a.task t join fetch t.unit u join fetch u.module where a.studentId in :studentIds")
        List<CompetenceAssessment> findByStudents(@Param("studentIds") Collection<Long> studentIds);

        long countByTaskId(Long taskId);
    }
}
