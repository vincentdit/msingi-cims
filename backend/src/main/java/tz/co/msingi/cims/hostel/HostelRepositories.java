package tz.co.msingi.cims.hostel;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class HostelRepositories {

    private HostelRepositories() {
    }

    public interface FeeCategoryRepository extends JpaRepository<HostelFeeCategory, Long> {
        List<HostelFeeCategory> findAllByOrderByCodeAsc();

        Optional<HostelFeeCategory> findByCodeIgnoreCase(String code);
    }

    public interface HostelRepository extends JpaRepository<Hostel, Long> {
        List<Hostel> findAllByOrderByNameAsc();

        Optional<Hostel> findByCodeIgnoreCase(String code);
    }

    public interface RoomRepository extends JpaRepository<HostelRoom, Long> {
        @EntityGraph(attributePaths = {"hostel", "feeCategory"})
        List<HostelRoom> findByHostelIdOrderByBlockAscFloorAscRoomNoAsc(Long hostelId);

        @EntityGraph(attributePaths = {"hostel", "feeCategory"})
        List<HostelRoom> findAllByOrderByIdAsc();

        boolean existsByHostelIdAndBlockAndRoomNo(Long hostelId, String block, String roomNo);
    }

    public interface BedRepository extends JpaRepository<HostelBed, Long> {
        @Query("select b from HostelBed b join fetch b.room r join fetch r.hostel h join fetch r.feeCategory")
        List<HostelBed> findAllWithRooms();
    }

    public interface WindowRepository extends JpaRepository<HostelWindow, Long> {
        @EntityGraph(attributePaths = "academicYear")
        List<HostelWindow> findAllByOrderByOpensOnDesc();
    }

    public interface ApplicationRepository extends JpaRepository<HostelApplication, Long> {
        List<HostelApplication> findByWindowIdOrderBySubmittedAtAsc(Long windowId);

        Optional<HostelApplication> findByWindowIdAndStudentId(Long windowId, Long studentId);

        List<HostelApplication> findByStudentIdOrderBySubmittedAtDesc(Long studentId);
    }

    public interface AllocationRepository extends JpaRepository<HostelAllocation, Long> {
        @Query("select a from HostelAllocation a join fetch a.bed b join fetch b.room r join fetch r.hostel where a.windowId = :windowId")
        List<HostelAllocation> findByWindow(@Param("windowId") Long windowId);

        @Query("select a from HostelAllocation a join fetch a.bed b join fetch b.room r join fetch r.hostel where a.status in :statuses")
        List<HostelAllocation> findByStatusIn(@Param("statuses") Collection<HostelAllocation.Status> statuses);

        @Query("select a from HostelAllocation a join fetch a.bed b join fetch b.room r join fetch r.hostel where a.studentId = :studentId order by a.allocatedAt desc")
        List<HostelAllocation> findByStudent(@Param("studentId") Long studentId);

        @Query("select a from HostelAllocation a where a.status = :status and a.payBy < :today")
        List<HostelAllocation> findOverdue(@Param("status") HostelAllocation.Status status, @Param("today") LocalDate today);

        Optional<HostelAllocation> findFirstByApplicationIdOrderByAllocatedAtDesc(Long applicationId);

        boolean existsByBedId(Long bedId);
    }
}
