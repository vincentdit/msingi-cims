package tz.co.msingi.cims.admission.verification;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public final class VerificationRepositories {

    private VerificationRepositories() {
    }

    public interface NactvetSubmissionRepository extends JpaRepository<NactvetSubmission, Long> {
        List<NactvetSubmission> findByWindowIdOrderByBatchNoDesc(Long windowId);

        @Query("select coalesce(max(s.batchNo), 0) from NactvetSubmission s where s.window.id = :windowId")
        int maxBatchNo(Long windowId);
    }

    public interface NactvetSubmissionItemRepository extends JpaRepository<NactvetSubmissionItem, Long> {
        @EntityGraph(attributePaths = {"application", "application.applicant", "windowProgramme", "windowProgramme.programme"})
        List<NactvetSubmissionItem> findBySubmissionIdOrderByIdAsc(Long submissionId);

        @EntityGraph(attributePaths = {"application", "application.applicant", "windowProgramme", "windowProgramme.programme"})
        List<NactvetSubmissionItem> findBySubmissionIdAndOutcome(Long submissionId, NactvetSubmissionItem.Outcome outcome);

        /** Outcome counts per submission of a window: [submissionId, outcome, count]. */
        @Query("""
                select i.submission.id, i.outcome, count(i) from NactvetSubmissionItem i
                where i.submission.window.id = :windowId group by i.submission.id, i.outcome""")
        List<Object[]> outcomeCounts(Long windowId);

        /** Verification history of one application, newest first. */
        @EntityGraph(attributePaths = {"submission"})
        List<NactvetSubmissionItem> findByApplicationIdOrderByIdDesc(Long applicationId);
    }
}
