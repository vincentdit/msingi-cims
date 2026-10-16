package tz.co.msingi.cims.admission.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;
import tz.co.msingi.cims.admission.eligibility.EligibilityResult;
import tz.co.msingi.cims.admission.eligibility.EntryLevel;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Entry-rule test tool (FR-ADM-04): the Admissions Office types a sample applicant's results and sees, for every programme of a
 * window, whether the applicant is at the programme's entry level and meets its minimum entry requirements, condition by condition —
 * the same engine applicants meet. Changed rules for one programme can be tried before they are saved. Nothing is stored.
 */
@Service
@RequiredArgsConstructor
public class EntryTestService {

    private static final Set<String> CSEE = Set.of("A", "B+", "B", "C", "D", "E", "F");
    private static final Set<String> ACSEE = Set.of("A", "B", "C", "D", "E", "S", "F");

    private final WindowProgrammeRepository windowProgrammes;
    private final EligibilityEngine engine;

    public record TestSubject(@Size(max = 20) String code, @Size(max = 80) String name, @NotBlank @Size(max = 2) String grade) {
    }

    /** type CSEE or ACSEE with subjects; NTA or NVA with the award level. */
    public record TestQualification(@NotNull @Pattern(regexp = "CSEE|ACSEE|NTA|NVA") String type, @Min(1) @Max(8) Integer awardLevel,
                                    @Valid List<TestSubject> subjects) {
    }

    /** rules (optional): changed requirements to try for windowProgrammeId instead of the saved ones. */
    public record TestRequest(@NotNull @Size(min = 1, max = 6) @Valid List<TestQualification> qualifications, Long windowProgrammeId,
                              @Valid EntryRules rules) {
    }

    public record ProgrammeResult(Long windowProgrammeId, String code, String name, String awardFamily, int awardLevel, boolean atEntryLevel,
                                  boolean eligible, boolean triedChangedRules, String requirementSummary, EligibilityResult detail) {
    }

    public record TestResult(String entryLevel, String basis, int eligibleCount, List<ProgrammeResult> programmes) {
    }

    @Transactional(readOnly = true)
    public TestResult test(Long windowId, TestRequest req) {
        List<EligibilityEngine.Qualification> quals = req.qualifications().stream().map(EntryTestService::toEngine).toList();
        EntryLevel level = EntryLevel.of(quals);
        List<WindowProgramme> list = windowProgrammes.findByWindowIdOrderByIdAsc(windowId);
        if (req.windowProgrammeId() != null && list.stream().noneMatch(wp -> wp.getId().equals(req.windowProgrammeId()))) {
            throw new BusinessRuleException("INVALID_PROGRAMME", "That programme is not in this window");
        }
        List<ProgrammeResult> results = list.stream().map(wp -> {
            boolean changed = req.rules() != null && wp.getId().equals(req.windowProgrammeId());
            EntryRules rules = changed ? req.rules() : wp.getEntryRules();
            var p = wp.getProgramme();
            EligibilityResult r = engine.evaluate(p.getAwardFamily().name(), p.entryLevel(), rules, quals);
            return new ProgrammeResult(wp.getId(), p.getCode(), p.getName(), p.getAwardFamily().name(), p.entryLevel(),
                    level.allows(p.getAwardFamily().name(), p.entryLevel(), rules), r.eligible(), changed, rules == null ? null : rules.summary(), r);
        }).toList();
        return new TestResult(level.label(), level.basis(), (int) results.stream().filter(ProgrammeResult::eligible).count(), results);
    }

    private static EligibilityEngine.Qualification toEngine(TestQualification q) {
        String type = q.type();
        List<EligibilityEngine.Qualification.Subject> subjects = q.subjects() == null ? List.of() : q.subjects().stream().map(s -> {
            String grade = s.grade().trim().toUpperCase(Locale.ROOT);
            if (("CSEE".equals(type) && !CSEE.contains(grade)) || ("ACSEE".equals(type) && !ACSEE.contains(grade))) {
                throw new BusinessRuleException("INVALID_GRADE", "Grade " + s.grade() + " is not a " + type + " grade");
            }
            if ((s.name() == null || s.name().isBlank()) && (s.code() == null || s.code().isBlank())) {
                throw new BusinessRuleException("SUBJECT_REQUIRED", "Give each subject's name or NECTA code");
            }
            return new EligibilityEngine.Qualification.Subject(s.code(), s.name(), grade);
        }).toList();
        if (("NTA".equals(type) || "NVA".equals(type)) && q.awardLevel() == null) {
            throw new BusinessRuleException("LEVEL_REQUIRED", "Give the level of the " + type + " award");
        }
        return new EligibilityEngine.Qualification(type, q.awardLevel(), true, subjects);
    }
}
