package tz.co.msingi.cims.teaching;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TeachingRepositories {

    private TeachingRepositories() {
    }

    public interface ClassSessionRepository extends JpaRepository<ClassSession, Long> {
        List<ClassSession> findByOfferingIdOrderBySessionDateDescStartTimeDesc(Long offeringId);

        Optional<ClassSession> findByClientRef(UUID clientRef);

        boolean existsByOfferingIdAndSessionDateAndStartTime(Long offeringId, java.time.LocalDate date, java.time.LocalTime time);

        long countByOfferingId(Long offeringId);

        /** Sessions whose self check-in is open (not yet closed), for matching a student's code and for closing on time. */
        @Query("select s from ClassSession s join fetch s.offering o join fetch o.module where s.checkinOpenedAt is not null and s.checkinClosedAt is null")
        List<ClassSession> findOpenCheckIns();

        @Query("select s.offering.id, count(s), coalesce(sum(s.durationMinutes), 0) from ClassSession s "
                + "where s.offering.semester.id = :semesterId group by s.offering.id")
        List<Object[]> totalsBySemester(@Param("semesterId") Long semesterId);
    }

    public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {
        @Query("select r from AttendanceRecord r join fetch r.session s where s.offering.id = :offeringId")
        List<AttendanceRecord> findByOffering(@Param("offeringId") Long offeringId);

        @Query("select r from AttendanceRecord r join fetch r.session s where s.offering.id in :offeringIds")
        List<AttendanceRecord> findByOfferings(@Param("offeringIds") Collection<Long> offeringIds);

        @Query("select r from AttendanceRecord r join fetch r.session s where r.studentId = :studentId and s.offering.semester.id = :semesterId")
        List<AttendanceRecord> findByStudentAndSemester(@Param("studentId") Long studentId, @Param("semesterId") Long semesterId);
    }

    public interface AttendanceAlertRepository extends JpaRepository<AttendanceAlert, Long> {
        List<AttendanceAlert> findByOfferingId(Long offeringId);

        List<AttendanceAlert> findByOfferingIdIn(Collection<Long> offeringIds);
    }

    public interface ExamEligibilityRepository extends JpaRepository<ExamEligibility, Long> {
        List<ExamEligibility> findByOfferingId(Long offeringId);

        List<ExamEligibility> findByOfferingIdIn(Collection<Long> offeringIds);

        Optional<ExamEligibility> findByModuleRegistrationId(Long moduleRegistrationId);

        List<ExamEligibility> findByStudentId(Long studentId);

        boolean existsByOfferingId(Long offeringId);
    }

    public interface EligibilityExemptionRepository extends JpaRepository<EligibilityExemption, Long> {
        List<EligibilityExemption> findByOfferingIdOrderByRequestedAtDesc(Long offeringId);

        List<EligibilityExemption> findByStatusOrderByRequestedAtAsc(EligibilityExemption.Status status);

        List<EligibilityExemption> findTop100ByOrderByRequestedAtDesc();

        List<EligibilityExemption> findByOfferingIdInAndStatus(Collection<Long> offeringIds, EligibilityExemption.Status status);

        Optional<EligibilityExemption> findFirstByModuleRegistrationIdAndStatusIn(Long moduleRegistrationId,
                                                                                    Collection<EligibilityExemption.Status> statuses);
    }
}
