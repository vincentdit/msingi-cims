package tz.co.msingi.cims.finance.fees;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public final class FeeRepositories {

    private FeeRepositories() {
    }

    public interface FeeItemRepository extends JpaRepository<FeeItem, Long> {
        List<FeeItem> findAllByOrderByAllocationPriorityAscCodeAsc();

        boolean existsByCodeIgnoreCase(String code);
    }

    public interface FeeStructureRepository extends JpaRepository<FeeStructure, Long> {
        @EntityGraph(attributePaths = {"programme", "academicYear", "lines", "lines.feeItem"})
        List<FeeStructure> findByAcademicYearIdOrderByProgrammeCodeAscYearOfStudyAscStructureVersionDesc(Long academicYearId);

        @EntityGraph(attributePaths = {"programme", "academicYear", "lines", "lines.feeItem"})
        Optional<FeeStructure> findByAcademicYearIdAndProgrammeIdAndYearOfStudyAndStatus(Long academicYearId, Long programmeId,
                                                                                          short yearOfStudy, FeeStructure.Status status);

        @Query("""
                select coalesce(max(f.structureVersion), 0) from FeeStructure f
                where f.academicYear.id = :academicYearId and f.programme.id = :programmeId and f.yearOfStudy = :yearOfStudy""")
        int maxVersion(Long academicYearId, Long programmeId, short yearOfStudy);
    }

    public interface ClearanceGateRepository extends JpaRepository<ClearanceGate, Long> {
        Optional<ClearanceGate> findByGate(ClearanceGate.Gate gate);
    }
}
