package tz.co.msingi.cims.admission.selection;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public final class SelectionRepositories {

    private SelectionRepositories() {
    }

    public interface SelectionRoundRepository extends JpaRepository<SelectionRound, Long> {
        List<SelectionRound> findByWindowIdOrderByRoundNoDesc(Long windowId);

        Optional<SelectionRound> findByWindowIdAndStatus(Long windowId, SelectionRound.Status status);

        @Query("select coalesce(max(r.roundNo), 0) from SelectionRound r where r.window.id = :windowId")
        int maxRoundNo(Long windowId);
    }

    public interface SelectionRepository extends JpaRepository<Selection, Long> {
        @EntityGraph(attributePaths = {"application", "application.applicant", "windowProgramme", "windowProgramme.programme"})
        List<Selection> findByRoundIdOrderByWindowProgrammeIdAscRankInProgrammeAscMeritScoreDesc(Long roundId);

        List<Selection> findByRoundIdAndStatus(Long roundId, Selection.Status status);

        @Modifying
        @Query("delete from Selection s where s.round.id = :roundId and s.status <> 'EXCLUDED'")
        void deleteAllButExclusions(Long roundId);

        /** How many of the round's (non-excluded) candidates listed each programme among their choices. */
        @Query("""
                select c.windowProgramme.id, count(c) from ApplicationChoice c
                where c.application.id in (select s.application.id from Selection s where s.round.id = :roundId and s.status <> 'EXCLUDED')
                group by c.windowProgramme.id""")
        List<Object[]> applicantsByProgramme(Long roundId);
    }
}
