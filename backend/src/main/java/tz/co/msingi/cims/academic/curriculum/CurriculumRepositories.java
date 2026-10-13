package tz.co.msingi.cims.academic.curriculum;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class CurriculumRepositories {

    private CurriculumRepositories() {
    }

    public interface SemesterRepository extends JpaRepository<Semester, Long> {
        @EntityGraph(attributePaths = "academicYear")
        List<Semester> findAllByOrderByStartDateDesc();

        List<Semester> findByAcademicYearIdOrderBySemesterNoAsc(Long academicYearId);

        boolean existsByAcademicYearIdAndSemesterNo(Long academicYearId, short semesterNo);
    }

    public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
        @EntityGraph(attributePaths = "semester")
        List<CalendarEvent> findByAcademicYearIdOrderByStartDateAscIdAsc(Long academicYearId);
    }

    public interface ModuleRepository extends JpaRepository<Module, Long> {
        @EntityGraph(attributePaths = "department")
        List<Module> findAllByOrderByCodeAsc();

        Optional<Module> findByCodeIgnoreCase(String code);
    }

    public interface CurriculumRepository extends JpaRepository<Curriculum, Long> {
        @EntityGraph(attributePaths = {"programme", "effectiveYear"})
        List<Curriculum> findByProgrammeIdOrderByCurriculumVersionDesc(Long programmeId);

        @EntityGraph(attributePaths = {"programme", "effectiveYear"})
        List<Curriculum> findAllByOrderByProgrammeIdAscCurriculumVersionDesc();

        @EntityGraph(attributePaths = {"effectiveYear"})
        List<Curriculum> findByProgrammeIdAndStatus(Long programmeId, Curriculum.Status status);

        boolean existsByProgrammeIdAndStatus(Long programmeId, Curriculum.Status status);
    }

    public interface CurriculumModuleRepository extends JpaRepository<CurriculumModule, Long> {
        @EntityGraph(attributePaths = "module")
        List<CurriculumModule> findByCurriculumIdAndYearOfStudyAndSemesterNo(Long curriculumId, short yearOfStudy, short semesterNo);

        long countByModuleId(Long moduleId);

        /** Modules taught in any curriculum of these programmes (what a Programme Coordinator or Head of Department follows). */
        @org.springframework.data.jpa.repository.Query("select distinct cm.module.id from CurriculumModule cm where cm.curriculum.programme.id in :programmeIds")
        List<Long> moduleIdsOfProgrammes(java.util.Collection<Long> programmeIds);
    }

    public interface ElectiveRuleRepository extends JpaRepository<ElectiveRule, Long> {
        Optional<ElectiveRule> findByCurriculumIdAndYearOfStudyAndSemesterNo(Long curriculumId, short yearOfStudy, short semesterNo);
    }
}
