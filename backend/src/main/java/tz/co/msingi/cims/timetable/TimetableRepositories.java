package tz.co.msingi.cims.timetable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public final class TimetableRepositories {

    private TimetableRepositories() {
    }

    public interface TeachingVenueRepository extends JpaRepository<TeachingVenue, Long> {
        List<TeachingVenue> findAllByOrderByCodeAsc();

        Optional<TeachingVenue> findByCodeIgnoreCase(String code);
    }

    public interface TimetableSlotRepository extends JpaRepository<TimetableSlot, Long> {
        @Query("select s from TimetableSlot s join fetch s.offering o join fetch o.module join fetch s.venue where o.semester.id = :semesterId "
                + "order by s.dayOfWeek, s.startTime, s.id")
        List<TimetableSlot> findBySemester(@Param("semesterId") Long semesterId);

        long countByVenueId(Long venueId);
    }

    public interface ExamSittingRepository extends JpaRepository<ExamSitting, Long> {
        @Query("select distinct s from ExamSitting s join fetch s.offering o join fetch o.module left join fetch s.venues v left join fetch v.venue "
                + "where o.semester.id = :semesterId order by s.sittingDate, s.startTime, s.id")
        List<ExamSitting> findBySemester(@Param("semesterId") Long semesterId);

        boolean existsByOfferingIdAndPaperIgnoreCase(Long offeringId, String paper);
    }
}
