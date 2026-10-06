package tz.co.msingi.cims.admission.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public final class AdmissionRepositories {

    private AdmissionRepositories() {
    }

    public interface AdmissionWindowRepository extends JpaRepository<AdmissionWindow, Long> {
        @EntityGraph(attributePaths = {"academicYear", "intake"})
        List<AdmissionWindow> findAllByOrderByOpensAtDesc();

        @EntityGraph(attributePaths = {"academicYear", "intake"})
        @Query("select w from AdmissionWindow w where w.status = 'PUBLISHED' and w.opensAt <= :now and w.closesAt > :now order by w.closesAt")
        List<AdmissionWindow> findOpen(OffsetDateTime now);
    }

    public interface WindowProgrammeRepository extends JpaRepository<WindowProgramme, Long> {
        @EntityGraph(attributePaths = {"programme", "programme.department"})
        List<WindowProgramme> findByWindowIdOrderByIdAsc(Long windowId);

        boolean existsByWindowIdAndProgrammeId(Long windowId, Long programmeId);
    }

    public interface ApplicantRepository extends JpaRepository<Applicant, Long> {
        Optional<Applicant> findByUserId(Long userId);
    }

    public interface ApplicantQualificationRepository extends JpaRepository<ApplicantQualification, Long> {
        @EntityGraph(attributePaths = "subjects")
        List<ApplicantQualification> findByApplicantIdOrderByIdAsc(Long applicantId);

        boolean existsByApplicantIdAndTypeAndIndexNumber(Long applicantId, ApplicantQualification.Type type, String indexNumber);
    }

    public interface ApplicationRepository extends JpaRepository<Application, Long> {
        @EntityGraph(attributePaths = {"window", "window.academicYear", "window.intake"})
        List<Application> findByApplicantIdOrderByCreatedAtDesc(Long applicantId);

        Optional<Application> findByApplicantIdAndWindowId(Long applicantId, Long windowId);

        @EntityGraph(attributePaths = {"applicant", "window"})
        @Query("""
                select a from Application a
                where (:windowId is null or a.window.id = :windowId)
                  and (:status is null or a.status = :status)
                  and (:q is null or lower(a.applicant.firstName) like lower(concat('%', cast(:q as string), '%'))
                       or lower(a.applicant.surname) like lower(concat('%', cast(:q as string), '%'))
                       or a.referenceNo like concat('%', cast(:q as string), '%')
                       or a.applicant.phone like concat('%', cast(:q as string), '%'))""")
        Page<Application> search(Long windowId, Application.Status status, String q, Pageable pageable);

        long countByWindowIdAndStatus(Long windowId, Application.Status status);

        long countByWindowId(Long windowId);
    }
}
