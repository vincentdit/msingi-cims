package tz.co.msingi.cims.admission.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
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

        @EntityGraph(attributePaths = {"academicYear", "intake"})
        @Query("select w from AdmissionWindow w where w.status = 'PUBLISHED' and w.opensAt > :now order by w.opensAt")
        List<AdmissionWindow> findUpcoming(OffsetDateTime now);

        @Query("select w from AdmissionWindow w where w.status = 'PUBLISHED' and w.closesAt <= :now")
        List<AdmissionWindow> findPastClosingDate(OffsetDateTime now);
    }

    public interface AdmissionWindowEventRepository extends JpaRepository<AdmissionWindowEvent, Long> {
        List<AdmissionWindowEvent> findByWindowIdOrderByAtDescIdDesc(Long windowId);
    }

    public interface WindowProgrammeRepository extends JpaRepository<WindowProgramme, Long> {
        @EntityGraph(attributePaths = {"programme", "programme.department"})
        List<WindowProgramme> findByWindowIdOrderByIdAsc(Long windowId);

        boolean existsByWindowIdAndProgrammeId(Long windowId, Long programmeId);
    }

    public interface ApplicantRepository extends JpaRepository<Applicant, Long> {
        Optional<Applicant> findByUserId(Long userId);

        @EntityGraph(attributePaths = "user")
        Optional<Applicant> findByNectaIndexNumberAndNectaExamYear(String indexNumber, Short examYear);

        @EntityGraph(attributePaths = "user")
        List<Applicant> findByNectaIndexNumber(String indexNumber);
    }

    public interface ApplicantQualificationRepository extends JpaRepository<ApplicantQualification, Long> {
        @EntityGraph(attributePaths = "subjects")
        List<ApplicantQualification> findByApplicantIdOrderByIdAsc(Long applicantId);

        @EntityGraph(attributePaths = "subjects")
        List<ApplicantQualification> findByApplicantIdIn(java.util.Collection<Long> applicantIds);

        boolean existsByApplicantIdAndTypeAndIndexNumber(Long applicantId, ApplicantQualification.Type type, String indexNumber);

        /** Declared qualifications of applicants with an application in the window, in a given check state. */
        @EntityGraph(attributePaths = {"subjects", "applicant"})
        @Query("select q from ApplicantQualification q where q.checkStatus = :status and q.source = 'MANUAL' and exists "
                + "(select a.id from Application a where a.applicant = q.applicant and a.window.id = :windowId) order by q.createdAt")
        List<ApplicantQualification> findForCheck(Long windowId, ApplicantQualification.CheckStatus status);
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

        @EntityGraph(attributePaths = {"applicant", "choices", "choices.windowProgramme"})
        List<Application> findByWindowIdAndStatus(Long windowId, Application.Status status);

        long countByWindowIdAndStatus(Long windowId, Application.Status status);

        long countByWindowId(Long windowId);

        /** Applications of the window in the given statuses, with what the verification screens show. */
        @EntityGraph(attributePaths = {"applicant", "selectedWindowProgramme", "selectedWindowProgramme.programme"})
        List<Application> findByWindowIdAndStatusInOrderByReferenceNoAsc(Long windowId, Collection<Application.Status> statuses);

        /** Places held per window programme (BP-04: declined, lapsed and rejected applicants release theirs). */
        @Query("""
                select a.selectedWindowProgramme.id, count(a) from Application a
                where a.window.id = :windowId and a.status in :statuses
                  and (:excludeRoundId is null or a.selectionRoundId <> :excludeRoundId)
                group by a.selectedWindowProgramme.id""")
        List<Object[]> placesHeld(Long windowId, Collection<Application.Status> statuses, Long excludeRoundId);

        @EntityGraph(attributePaths = {"applicant", "window", "selectedWindowProgramme", "selectedWindowProgramme.programme"})
        List<Application> findByWindowAcademicYearIdAndStatus(Long academicYearId, Application.Status status);

        @EntityGraph(attributePaths = {"applicant", "selectedWindowProgramme", "selectedWindowProgramme.programme"})
        List<Application> findByStatusAndOfferExpiresAtBefore(Application.Status status, OffsetDateTime cutoff);
    }
}
