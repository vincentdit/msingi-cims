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

        Optional<ResultSheet> findBySemesterIdAndProgrammeIdAndYearOfStudyAndRound(Long semesterId, Long programmeId, short yearOfStudy,
                                                                                    ResultSheet.Round round);

        long countByRegulationSetId(Long regulationSetId);
    }

    public interface SemesterResultRepository extends JpaRepository<SemesterResult, Long> {
        @EntityGraph(attributePaths = {"sheet", "sheet.semester", "sheet.semester.academicYear"})
        List<SemesterResult> findByStudentIdOrderByIdAsc(Long studentId);
    }

    public interface ResitSessionRepository extends JpaRepository<ResitSession, Long> {
        Optional<ResitSession> findBySemesterId(Long semesterId);
    }

    public interface ResitRepository extends JpaRepository<Resit, Long> {
        @EntityGraph(attributePaths = {"moduleResult", "session"})
        List<Resit> findByOfferingIdAndStatus(Long offeringId, Resit.Status status);

        @EntityGraph(attributePaths = {"moduleResult", "session"})
        List<Resit> findByStudentIdAndStatus(Long studentId, Resit.Status status);

        @EntityGraph(attributePaths = {"moduleResult"})
        List<Resit> findBySessionIdAndStatus(Long sessionId, Resit.Status status);

        Optional<Resit> findByModuleResultId(Long moduleResultId);
    }

    public interface ResultAmendmentRepository extends JpaRepository<ResultAmendment, Long> {
        @EntityGraph(attributePaths = "moduleResult")
        List<ResultAmendment> findByStatusOrderByRequestedAtDesc(ResultAmendment.Status status);

        @EntityGraph(attributePaths = "moduleResult")
        List<ResultAmendment> findTop100ByOrderByRequestedAtDesc();

        boolean existsByModuleResultIdAndStatus(Long moduleResultId, ResultAmendment.Status status);
    }

    public interface AcademicDocumentRepository extends JpaRepository<AcademicDocument, Long> {
        List<AcademicDocument> findByStudentIdOrderByIssuedAtDesc(Long studentId);

        Optional<AcademicDocument> findFirstByStudentIdAndKindAndStatus(Long studentId, AcademicDocument.Kind kind, AcademicDocument.Status status);

        Optional<AcademicDocument> findByVerificationCode(String verificationCode);
    }

    public interface NactvetResultSubmissionRepository extends JpaRepository<NactvetResultSubmission, Long> {
        List<NactvetResultSubmission> findBySheetIdOrderBySubmittedAtDesc(Long sheetId);
    }
}
