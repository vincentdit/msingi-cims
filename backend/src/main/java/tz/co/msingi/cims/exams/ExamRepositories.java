package tz.co.msingi.cims.exams;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class ExamRepositories {

    private ExamRepositories() {
    }

    public interface RegulationSetRepository extends JpaRepository<RegulationSet, Long> {
        @EntityGraph(attributePaths = "effectiveYear")
        List<RegulationSet> findAllByOrderByAwardFamilyAscLevelFromAscIdDesc();
    }

    public interface ExamSettingsRepository extends JpaRepository<ExamSettings, Long> {
        Optional<ExamSettings> findFirstByOrderByIdAsc();
    }

    public interface ModuleOfferingRepository extends JpaRepository<ModuleOffering, Long> {
        @EntityGraph(attributePaths = {"module", "module.department", "semester", "regulationSet"})
        List<ModuleOffering> findBySemesterIdOrderByIdAsc(Long semesterId);

        Optional<ModuleOffering> findBySemesterIdAndModuleId(Long semesterId, Long moduleId);

        @Query("select o from ModuleOffering o join o.staff s where s.userId = :userId and o.semester.id = :semesterId")
        List<ModuleOffering> findForStaff(@Param("userId") Long userId, @Param("semesterId") Long semesterId);

        long countByRegulationSetId(Long regulationSetId);
    }

    public interface AssessmentScoreRepository extends JpaRepository<AssessmentScore, Long> {
        @Query("select s from AssessmentScore s where s.component.offering.id = :offeringId")
        List<AssessmentScore> findByOffering(@Param("offeringId") Long offeringId);

        long countByComponentId(Long componentId);
    }

    public interface ModuleResultRepository extends JpaRepository<ModuleResult, Long> {
        List<ModuleResult> findByOfferingId(Long offeringId);

        List<ModuleResult> findByModuleRegistrationIdIn(Collection<Long> moduleRegistrationIds);

        Optional<ModuleResult> findByModuleRegistrationId(Long moduleRegistrationId);

        List<ModuleResult> findByStudentId(Long studentId);

        long countByRegulationSetId(Long regulationSetId);
    }

    public interface ResultSheetRepository extends JpaRepository<ResultSheet, Long> {
        @EntityGraph(attributePaths = {"programme", "semester", "regulationSet"})
        List<ResultSheet> findBySemesterIdOrderByProgrammeIdAscYearOfStudyAsc(Long semesterId);

        Optional<ResultSheet> findBySemesterIdAndProgrammeIdAndYearOfStudy(Long semesterId, Long programmeId, short yearOfStudy);

        long countByRegulationSetId(Long regulationSetId);
    }

    public interface SemesterResultRepository extends JpaRepository<SemesterResult, Long> {
        @EntityGraph(attributePaths = {"sheet", "sheet.semester", "sheet.semester.academicYear"})
        List<SemesterResult> findByStudentIdOrderByIdAsc(Long studentId);
    }
}
