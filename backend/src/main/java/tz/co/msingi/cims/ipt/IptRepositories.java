package tz.co.msingi.cims.ipt;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class IptRepositories {

    private IptRepositories() {
    }

    public interface OrganisationRepository extends JpaRepository<PlacementOrganisation, Long> {
        List<PlacementOrganisation> findAllByOrderByNameAsc();

        Optional<PlacementOrganisation> findFirstByNameIgnoreCase(String name);
    }

    public interface PeriodRepository extends JpaRepository<IptPeriod, Long> {
        @EntityGraph(attributePaths = {"semester", "semester.academicYear", "module"})
        List<IptPeriod> findAllByOrderByStartsOnDesc();
    }

    public interface PlacementRepository extends JpaRepository<IptPlacement, Long> {
        @EntityGraph(attributePaths = {"organisation", "period"})
        List<IptPlacement> findByPeriodIdOrderByRequestedAtAsc(Long periodId);

        @EntityGraph(attributePaths = {"organisation", "period", "period.semester"})
        List<IptPlacement> findByStudentIdOrderByRequestedAtDesc(Long studentId);

        @EntityGraph(attributePaths = {"organisation", "period"})
        List<IptPlacement> findByInstitutionSupervisorIdOrderByStartsOnDesc(Long supervisorId);

        @Query("select count(p) from IptPlacement p where p.period.id = :periodId and p.organisation.id = :orgId and p.status in :statuses")
        long countLive(@Param("periodId") Long periodId, @Param("orgId") Long orgId, @Param("statuses") Collection<IptPlacement.Status> statuses);
    }

    public interface LogbookRepository extends JpaRepository<LogbookEntry, Long> {
        List<LogbookEntry> findByPlacementIdOrderByWeekNoAsc(Long placementId);

        List<LogbookEntry> findByPlacementIdIn(Collection<Long> placementIds);

        Optional<LogbookEntry> findByPlacementIdAndWeekNo(Long placementId, short weekNo);
    }

    public interface VisitRepository extends JpaRepository<SupervisionVisit, Long> {
        List<SupervisionVisit> findByPlacementIdOrderByVisitedOnDesc(Long placementId);
    }

    public interface AssessmentRepository extends JpaRepository<IptAssessment, Long> {
        List<IptAssessment> findByPlacementId(Long placementId);

        List<IptAssessment> findByPlacementIdIn(Collection<Long> placementIds);

        Optional<IptAssessment> findByPlacementIdAndKind(Long placementId, IptAssessment.Kind kind);
    }
}
