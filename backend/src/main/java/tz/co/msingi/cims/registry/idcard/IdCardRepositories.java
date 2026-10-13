package tz.co.msingi.cims.registry.idcard;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class IdCardRepositories {

    private IdCardRepositories() {
    }

    public interface StudentPhotoRepository extends JpaRepository<StudentPhoto, Long> {
        Optional<StudentPhoto> findByStudentId(Long studentId);

        boolean existsByStudentId(Long studentId);

        @Query("select p.studentId from StudentPhoto p where p.studentId in :ids")
        Set<Long> studentsWithPhoto(Collection<Long> ids);
    }

    public interface IdCardRepository extends JpaRepository<IdCard, Long> {
        @EntityGraph(attributePaths = {"student", "student.programme"})
        List<IdCard> findByStudentIdOrderByIssuedAtDesc(Long studentId);

        Optional<IdCard> findByStudentIdAndStatus(Long studentId, IdCard.Status status);

        @EntityGraph(attributePaths = {"student", "student.programme"})
        Optional<IdCard> findByVerificationCode(String verificationCode);

        @Query("select c.student.id from IdCard c where c.status = tz.co.msingi.cims.registry.idcard.IdCard.Status.VALID and c.student.id in :ids")
        Set<Long> studentsWithValidCard(Collection<Long> ids);
    }
}
