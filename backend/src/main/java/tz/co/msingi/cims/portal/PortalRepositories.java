package tz.co.msingi.cims.portal;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class PortalRepositories {

    private PortalRepositories() {
    }

    public interface NoticeRepository extends JpaRepository<Notice, Long> {

        @EntityGraph(attributePaths = "programme")
        List<Notice> findAllByOrderByCreatedAtDesc();

        /** Live notices for a student of the programme and year of study, pinned first, newest first. */
        @EntityGraph(attributePaths = "programme")
        @Query("""
                select n from Notice n
                where n.status = tz.co.msingi.cims.portal.Notice.Status.PUBLISHED and n.publishAt <= :now
                  and (n.expiresAt is null or n.expiresAt > :now)
                  and (n.programme is null or n.programme.id = :programmeId)
                  and (n.yearOfStudy is null or n.yearOfStudy = :yearOfStudy)
                order by n.pinned desc, n.publishAt desc""")
        List<Notice> liveFor(OffsetDateTime now, Long programmeId, Short yearOfStudy);
    }

    public interface NoticeReadRepository extends JpaRepository<NoticeRead, NoticeRead.Key> {

        @Query("select r.id.noticeId from NoticeRead r where r.id.userId = :userId and r.id.noticeId in :noticeIds")
        List<Long> readBy(Long userId, Collection<Long> noticeIds);

        long countByIdNoticeId(Long noticeId);
    }

    public interface StudentChangeRequestRepository extends JpaRepository<StudentChangeRequest, Long> {

        @EntityGraph(attributePaths = {"student", "student.programme"})
        List<StudentChangeRequest> findByStatusOrderBySubmittedAtAsc(StudentChangeRequest.Status status);

        @EntityGraph(attributePaths = {"student", "student.programme"})
        List<StudentChangeRequest> findTop50ByStatusNotOrderByDecidedAtDesc(StudentChangeRequest.Status status);

        List<StudentChangeRequest> findByStudentIdOrderBySubmittedAtDesc(Long studentId);

        Optional<StudentChangeRequest> findByStudentIdAndStatus(Long studentId, StudentChangeRequest.Status status);

        long countByStatus(StudentChangeRequest.Status status);
    }
}
