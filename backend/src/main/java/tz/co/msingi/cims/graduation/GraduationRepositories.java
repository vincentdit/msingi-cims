package tz.co.msingi.cims.graduation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class GraduationRepositories {

    private GraduationRepositories() {
    }

    public interface ClearanceOfficeRepository extends JpaRepository<ClearanceOffice, Long> {
        List<ClearanceOffice> findAllByOrderBySortOrderAscIdAsc();

        Optional<ClearanceOffice> findByCodeIgnoreCase(String code);
    }

    public interface ClearanceRepository extends JpaRepository<Clearance, Long> {
        Optional<Clearance> findByStudentId(Long studentId);

        List<Clearance> findByStudentIdIn(Collection<Long> studentIds);

        List<Clearance> findAllByOrderByStartedAtDesc();
    }

    public interface ClearanceItemRepository extends JpaRepository<ClearanceItem, Long> {
        @Query("select i from ClearanceItem i join fetch i.clearance c where i.office.id = :officeId order by c.startedAt asc")
        List<ClearanceItem> findByOffice(@Param("officeId") Long officeId);

        long countByOfficeId(Long officeId);
    }

    public interface GraduationRepository extends JpaRepository<Graduation, Long> {
        List<Graduation> findAllByOrderByCreatedAtDesc();
    }

    public interface GraduandRepository extends JpaRepository<Graduand, Long> {
        List<Graduand> findByGraduationId(Long graduationId);

        Optional<Graduand> findByStudentId(Long studentId);

        Optional<Graduand> findByVerificationCode(String verificationCode);

        long countByGraduationId(Long graduationId);
    }
}
