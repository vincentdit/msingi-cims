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

        @Query("""
                select coalesce(max(f.structureVersion), 0) from FeeStructure f
                where f.academicYear.id = :academicYearId and f.programme.id = :programmeId and f.yearOfStudy = :yearOfStudy
                  and coalesce(f.intakeId, 0) = coalesce(:intakeId, 0) and coalesce(f.studentCategoryId, 0) = coalesce(:categoryId, 0)""")
        int maxVersion(Long academicYearId, Long programmeId, short yearOfStudy, Long intakeId, Long categoryId);

        /** Structures of one programme and year of study in a status, across intakes and categories (callers pick the variant). */
        @EntityGraph(attributePaths = {"programme", "academicYear", "lines", "lines.feeItem"})
        List<FeeStructure> findAllByAcademicYearIdAndProgrammeIdAndYearOfStudyAndStatus(Long academicYearId, Long programmeId,
                                                                                         short yearOfStudy, FeeStructure.Status status);
    }

    public interface ClearanceGateRepository extends JpaRepository<ClearanceGate, Long> {
        Optional<ClearanceGate> findByGate(ClearanceGate.Gate gate);
    }
}
