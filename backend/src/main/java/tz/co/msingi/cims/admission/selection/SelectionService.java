package tz.co.msingi.cims.admission.selection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.ApplicationChoice;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;
import tz.co.msingi.cims.admission.selection.SelectionDtos.Row;
import tz.co.msingi.cims.admission.selection.SelectionDtos.RoundDetail;
import tz.co.msingi.cims.admission.selection.SelectionDtos.RoundSummary;
import tz.co.msingi.cims.admission.selection.SelectionRepositories.SelectionRepository;
import tz.co.msingi.cims.admission.selection.SelectionRepositories.SelectionRoundRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selection rounds (BP-03): the Admissions Office runs a proposal, the Admissions Committee
 * reviews it (excluding applicants with a recorded reason if needed) and approves it.
 * Approval is made by a different person from the one who ran the proposal (segregation of duties).
 */
@Service
@RequiredArgsConstructor
public class SelectionService {

    private final SelectionRoundRepository rounds;
    private final SelectionRepository selections;
    private final AdmissionWindowRepository windows;
    private final WindowProgrammeRepository windowProgrammes;
    private final ApplicationRepository applications;
    private final ApplicantQualificationRepository qualifications;
    private final EligibilityEngine eligibility;
    private final MeritScorer merit;
    private final AuditService audit;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<RoundSummary> list(Long windowId) {
        requireWindow(windowId);
        return rounds.findByWindowIdOrderByRoundNoDesc(windowId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public RoundDetail detail(Long roundId) {
        SelectionRound round = requireRound(roundId);
        Long windowId = round.getWindow().getId();
        List<Selection> rows = selections.findByRoundIdOrderByWindowProgrammeIdAscRankInProgrammeAscMeritScoreDesc(roundId);
        Map<Long, Long> filledBefore = round.getStatus() == SelectionRound.Status.APPROVED
                ? filledExcluding(windowId, roundId) : toMap(selections.approvedPlacesByProgramme(windowId));
        Map<Long, Long> applicants = toMap(selections.applicantsByProgramme(roundId));

        List<SelectionDtos.ProgrammeResult> programmes = new ArrayList<>();
        for (WindowProgramme wp : windowProgrammes.findByWindowIdOrderByIdAsc(windowId)) {
            List<Row> selected = rows.stream()
                    .filter(s -> s.getStatus() == Selection.Status.SELECTED && s.getWindowProgramme().getId().equals(wp.getId()))
                    .sorted(Comparator.comparing(Selection::getRankInProgramme))
                    .map(this::row).toList();
            long before = filledBefore.getOrDefault(wp.getId(), 0L);
            programmes.add(new SelectionDtos.ProgrammeResult(wp.getId(), wp.getProgramme().getCode(), wp.getProgramme().getName(),
                    wp.getCapacity(), before, Math.max(0, wp.getCapacity() - before), applicants.getOrDefault(wp.getId(), 0L),
                    selected.size(), countBy(selected, r -> sexLabel(r.sex())), selected));
        }
        List<Row> notSelected = rows.stream().filter(s -> s.getStatus() == Selection.Status.NOT_SELECTED)
                .sorted(Comparator.comparing(Selection::getMeritScore).reversed()).map(this::row).toList();
        List<Row> excluded = rows.stream().filter(s -> s.getStatus() == Selection.Status.EXCLUDED).map(this::row).toList();
        List<Row> allSelected = programmes.stream().flatMap(p -> p.selected().stream()).toList();

        SelectionDtos.Totals totals = new SelectionDtos.Totals(rows.size(), allSelected.size(), notSelected.size(), excluded.size());
        boolean canApprove = round.getStatus() == SelectionRound.Status.PROPOSED
                && !round.getProposedBy().equals(CurrentUser.id().orElse(-1L));
        return new RoundDetail(summary(round), MeritScorer.FORMULA, totals, programmes, notSelected, excluded,
                countBy(allSelected, r -> sexLabel(r.sex())), countBy(allSelected, r -> r.region() == null ? "Not stated" : r.region()),
                canApprove);
    }

    // ------------------------------------------------------------------ commands

    /** Runs a selection proposal for the window, or re-runs the round already awaiting approval. */
    @Transactional
    public RoundDetail propose(Long windowId) {
        AdmissionWindow window = requireWindow(windowId);
        SelectionRound round = rounds.findByWindowIdAndStatus(windowId, SelectionRound.Status.PROPOSED).orElseGet(() -> {
            SelectionRound r = new SelectionRound();
            r.setWindow(window);
            r.setRoundNo((short) (rounds.maxRoundNo(windowId) + 1));
            return r;
        });
        round.setProposedAt(OffsetDateTime.now());
        round.setProposedBy(CurrentUser.requireId());
        round.setProposedByName(CurrentUser.name());
        round = rounds.saveAndFlush(round);
        int placed = allocate(round);
        audit.record("SELECTION_PROPOSED", "SelectionRound", round.getId(), Map.of("roundNo", round.getRoundNo(), "selected", placed));
        return detail(round.getId());
    }

    /** Committee review: remove an applicant from the round with a recorded reason, then re-allocate places. */
    @Transactional
    public RoundDetail exclude(Long roundId, Long applicationId, String reason) {
        SelectionRound round = requireProposed(roundId);
        Selection s = selectionFor(round, applicationId);
        s.setStatus(Selection.Status.EXCLUDED);
        s.setWindowProgramme(null);
        s.setChoiceOrder(null);
        s.setRankInProgramme(null);
        s.setExclusionReason(reason.trim());
        s.setExcludedByName(CurrentUser.name());
        selections.flush();
        allocate(round);
        audit.record("SELECTION_EXCLUDED", "Application", applicationId, Map.of("round", round.getRoundNo(), "reason", reason.trim()));
        return detail(roundId);
    }

    @Transactional
    public RoundDetail reinstate(Long roundId, Long applicationId) {
        SelectionRound round = requireProposed(roundId);
        Selection s = selectionFor(round, applicationId);
        if (s.getStatus() != Selection.Status.EXCLUDED) {
            throw new BusinessRuleException("NOT_EXCLUDED", "This applicant is not excluded");
        }
        selections.delete(s);
        selections.flush();
        allocate(round);
        audit.record("SELECTION_REINSTATED", "Application", applicationId, Map.of("round", round.getRoundNo()));
        return detail(roundId);
    }

    /** Admissions Committee approval (BP-03 step 5). Selected applicants move to SELECTED. */
    @Transactional
    public RoundDetail approve(Long roundId, String minuteRef, String notes) {
        SelectionRound round = requireProposed(roundId);
        Long approver = CurrentUser.requireId();
        if (round.getProposedBy().equals(approver)) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES",
                    "The person who ran the selection cannot also approve it. Ask the Admissions Committee chair to approve.");
        }
        List<Selection> selected = selections.findByRoundIdAndStatus(roundId, Selection.Status.SELECTED);
        if (selected.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_APPROVE", "No applicants are selected in this round");
        }
        for (Selection s : selected) {
            Application app = s.getApplication();
            if (app.getStatus() != Application.Status.SUBMITTED) {
                throw new BusinessRuleException("STALE_ROUND",
                        "Application " + app.getReferenceNo() + " changed since the proposal was run. Re-run the proposal.");
            }
            app.setStatus(Application.Status.SELECTED);
            app.setSelectedWindowProgramme(s.getWindowProgramme());
            app.setSelectionRoundId(round.getId());
        }
        round.setStatus(SelectionRound.Status.APPROVED);
        round.setApprovedAt(OffsetDateTime.now());
        round.setApprovedBy(approver);
        round.setApprovedByName(CurrentUser.name());
        round.setCommitteeMinuteRef(minuteRef.trim());
        round.setNotes(notes);
        audit.record("SELECTION_APPROVED", "SelectionRound", roundId,
                Map.of("roundNo", round.getRoundNo(), "selected", selected.size(), "minute", minuteRef.trim()));
        return detail(roundId);
    }

    @Transactional
    public RoundSummary cancel(Long roundId) {
        SelectionRound round = requireProposed(roundId);
        round.setStatus(SelectionRound.Status.CANCELLED);
        audit.record("SELECTION_CANCELLED", "SelectionRound", roundId, Map.of("roundNo", round.getRoundNo()));
        return summary(round);
    }

    /** Selection list as CSV for the Admissions Committee pack. */
    @Transactional(readOnly = true)
    public String exportCsv(Long roundId) {
        RoundDetail d = detail(roundId);
        StringBuilder sb = new StringBuilder("Programme,Rank,Reference,Applicant,Sex,Region,Phone,Merit,Choice,Status,Reason\n");
        for (SelectionDtos.ProgrammeResult p : d.programmes()) {
            for (Row r : p.selected()) {
                line(sb, p.programmeName(), r.rank(), r, "Selected", "");
            }
        }
        for (Row r : d.notSelected()) {
            line(sb, "", null, r, "Not selected", "");
        }
        for (Row r : d.excluded()) {
            line(sb, "", null, r, "Excluded", r.exclusionReason());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ allocation

    /** Recomputes the round's placements, keeping committee exclusions. Returns the number placed. */
    private int allocate(SelectionRound round) {
        Long windowId = round.getWindow().getId();
        Set<Long> excluded = selections.findByRoundIdAndStatus(round.getId(), Selection.Status.EXCLUDED).stream()
                .map(s -> s.getApplication().getId()).collect(Collectors.toSet());
        selections.deleteAllButExclusions(round.getId());
        selections.flush();

        List<WindowProgramme> offered = windowProgrammes.findByWindowIdOrderByIdAsc(windowId);
        Map<Long, WindowProgramme> wpById = offered.stream().collect(Collectors.toMap(WindowProgramme::getId, Function.identity()));
        Map<Long, Long> filled = toMap(selections.approvedPlacesByProgramme(windowId));
        Map<Long, Integer> capacity = new HashMap<>();
        offered.forEach(wp -> capacity.put(wp.getId(), (int) Math.max(0, wp.getCapacity() - filled.getOrDefault(wp.getId(), 0L))));

        List<Application> pool = applications.findByWindowIdAndStatus(windowId, Application.Status.SUBMITTED).stream()
                .filter(a -> !excluded.contains(a.getId())).toList();
        if (pool.isEmpty() && excluded.isEmpty()) {
            throw new BusinessRuleException("NO_CANDIDATES", "There are no submitted applications awaiting selection in this window");
        }
        Map<Long, List<EligibilityEngine.Qualification>> qualsByApplicant = qualifications
                .findByApplicantIdIn(pool.stream().map(a -> a.getApplicant().getId()).toList()).stream()
                .collect(Collectors.groupingBy(q -> q.getApplicant().getId(),
                        Collectors.mapping(ApplicantQualification::toEngine, Collectors.toList())));

        List<SelectionAllocator.Candidate> candidates = new ArrayList<>();
        Map<Long, Application> appById = new HashMap<>();
        Map<Long, java.math.BigDecimal> scoreById = new HashMap<>();
        for (Application a : pool) {
            List<EligibilityEngine.Qualification> quals = qualsByApplicant.getOrDefault(a.getApplicant().getId(), List.of());
            // Eligibility re-check against the current rules (BP-03 step 1)
            List<Long> eligibleChoices = a.getChoices().stream()
                    .sorted(Comparator.comparing(ApplicationChoice::getChoiceOrder))
                    .map(c -> wpById.get(c.getWindowProgramme().getId()))
                    .filter(wp -> wp != null && eligibility.evaluate(wp.getEntryRules(), quals).eligible())
                    .map(WindowProgramme::getId).toList();
            java.math.BigDecimal score = merit.score(quals);
            candidates.add(new SelectionAllocator.Candidate(a.getId(), score, a.getSubmittedAt(), eligibleChoices));
            appById.put(a.getId(), a);
            scoreById.put(a.getId(), score);
        }

        int placed = 0;
        for (SelectionAllocator.Placement p : SelectionAllocator.allocate(candidates, capacity)) {
            Selection s = new Selection();
            s.setRound(round);
            s.setApplication(appById.get(p.applicationId()));
            s.setMeritScore(scoreById.get(p.applicationId()));
            if (p.programmeId() != null) {
                s.setStatus(Selection.Status.SELECTED);
                s.setWindowProgramme(wpById.get(p.programmeId()));
                s.setChoiceOrder(p.choiceOrder().shortValue());
                s.setRankInProgramme(p.rankInProgramme());
                placed++;
            } else {
                s.setStatus(Selection.Status.NOT_SELECTED);
            }
            selections.save(s);
        }
        selections.flush();
        return placed;
    }

    // ------------------------------------------------------------------ helpers

    private AdmissionWindow requireWindow(Long id) {
        return windows.findById(id).orElseThrow(() -> new NotFoundException("Admission window", id));
    }

    private SelectionRound requireRound(Long id) {
        return rounds.findById(id).orElseThrow(() -> new NotFoundException("Selection round", id));
    }

    private SelectionRound requireProposed(Long id) {
        SelectionRound r = requireRound(id);
        if (r.getStatus() != SelectionRound.Status.PROPOSED) {
            throw new BusinessRuleException("ROUND_CLOSED", "This round has already been " + r.getStatus().name().toLowerCase());
        }
        return r;
    }

    private Selection selectionFor(SelectionRound round, Long applicationId) {
        return selections.findByRoundIdOrderByWindowProgrammeIdAscRankInProgrammeAscMeritScoreDesc(round.getId()).stream()
                .filter(s -> s.getApplication().getId().equals(applicationId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Applicant is not part of this round: " + applicationId));
    }

    private Map<Long, Long> filledExcluding(Long windowId, Long roundId) {
        Map<Long, Long> all = toMap(selections.approvedPlacesByProgramme(windowId));
        selections.findByRoundIdAndStatus(roundId, Selection.Status.SELECTED)
                .forEach(s -> all.merge(s.getWindowProgramme().getId(), -1L, Long::sum));
        return all;
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        rows.forEach(r -> m.put((Long) r[0], ((Number) r[1]).longValue()));
        return m;
    }

    private RoundSummary summary(SelectionRound r) {
        List<Selection> rows = selections.findByRoundIdOrderByWindowProgrammeIdAscRankInProgrammeAscMeritScoreDesc(r.getId());
        Map<Selection.Status, Long> counts = rows.stream().collect(Collectors.groupingBy(Selection::getStatus, Collectors.counting()));
        return new RoundSummary(r.getId(), r.getRoundNo(), r.getStatus().name(), r.getProposedAt(), r.getProposedByName(),
                r.getApprovedAt(), r.getApprovedByName(), r.getCommitteeMinuteRef(),
                counts.getOrDefault(Selection.Status.SELECTED, 0L), counts.getOrDefault(Selection.Status.NOT_SELECTED, 0L),
                counts.getOrDefault(Selection.Status.EXCLUDED, 0L));
    }

    private Row row(Selection s) {
        Application a = s.getApplication();
        var ap = a.getApplicant();
        return new Row(a.getId(), a.getReferenceNo(), ap.fullName(), ap.getPhone(), ap.getSex(), ap.getRegion(), s.getMeritScore(),
                s.getChoiceOrder() == null ? null : s.getChoiceOrder().intValue(), s.getRankInProgramme(),
                s.getWindowProgramme() == null ? null : s.getWindowProgramme().getId(),
                s.getWindowProgramme() == null ? null : s.getWindowProgramme().getProgramme().getName(),
                s.getExclusionReason(), s.getExcludedByName());
    }

    private static String sexLabel(String sex) {
        return "F".equals(sex) ? "Female" : "M".equals(sex) ? "Male" : "Not stated";
    }

    private static Map<String, Long> countBy(List<Row> rows, Function<Row, String> key) {
        Map<String, Long> m = rows.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
        return new LinkedHashMap<>(m);
    }

    private static void line(StringBuilder sb, String programme, Integer rank, Row r, String status, String reason) {
        sb.append(csv(programme)).append(',').append(rank == null ? "" : rank).append(',').append(csv(r.referenceNo())).append(',')
                .append(csv(r.applicantName())).append(',').append(csv(sexLabel(r.sex()))).append(',').append(csv(r.region())).append(',')
                .append(csv(r.phone())).append(',').append(r.meritScore()).append(',')
                .append(r.choiceOrder() == null ? "" : r.choiceOrder()).append(',').append(status).append(',').append(csv(reason)).append('\n');
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        // Neutralise spreadsheet formula injection
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}
