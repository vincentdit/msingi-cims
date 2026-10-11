package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.ClassificationDto;
import tz.co.msingi.cims.exams.ExamDtos.GradeBandDto;
import tz.co.msingi.cims.exams.ExamDtos.RegulationRequest;
import tz.co.msingi.cims.exams.ExamDtos.RegulationView;
import tz.co.msingi.cims.exams.ExamDtos.SettingsView;
import tz.co.msingi.cims.exams.ExamRepositories.ExamSettingsRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.ExamRepositories.RegulationSetRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResultSheetRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Examination regulations per award level (FR-ACA-05), versioned by the academic year they apply from. A set that has been used
 * to compute results cannot change: a new version is made for a later year, so earlier results stay reproducible.
 */
@Service
@RequiredArgsConstructor
public class RegulationService {

    private final RegulationSetRepository sets;
    private final ExamSettingsRepository settings;
    private final ModuleOfferingRepository offerings;
    private final ModuleResultRepository results;
    private final ResultSheetRepository sheets;
    private final AcademicYearRepository years;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<RegulationView> list() {
        return sets.findAllByOrderByAwardFamilyAscLevelFromAscIdDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RegulationView get(Long id) {
        return view(require(id));
    }

    @Transactional
    public RegulationView create(RegulationRequest req) {
        RegulationSet rs = new RegulationSet();
        rs.setCreatedByName(CurrentUser.name());
        apply(rs, req);
        sets.save(rs);
        audit.record("REGULATIONS_CREATED", "RegulationSet", rs.getId(), Map.of("name", rs.getName(), "from", rs.getEffectiveYear().getCode()));
        return view(rs);
    }

    @Transactional
    public RegulationView update(Long id, RegulationRequest req) {
        RegulationSet rs = require(id);
        if (inUse(rs)) {
            throw new BusinessRuleException("REGULATIONS_IN_USE",
                    "These regulations have been used for results and cannot change. Make a new version for a later academic year.");
        }
        apply(rs, req);
        sets.flush();
        audit.record("REGULATIONS_UPDATED", "RegulationSet", rs.getId(), Map.of("name", rs.getName()));
        return view(rs);
    }

    /** The regulations in force for a programme in an academic year: the latest version covering its level, effective by then. */
    @Transactional(readOnly = true)
    public RegulationSet forLevel(Programme.AwardFamily family, int level, AcademicYear year) {
        return sets.findAllByOrderByAwardFamilyAscLevelFromAscIdDesc().stream()
                .filter(r -> r.covers(family, level) && !r.getEffectiveYear().getStartDate().isAfter(year.getStartDate()))
                .max(Comparator.comparing((RegulationSet r) -> r.getEffectiveYear().getStartDate()))
                .orElseThrow(() -> new BusinessRuleException("NO_REGULATIONS", "No examination regulations cover " + family + " level " + level
                        + " for " + year.getCode() + ". Set them up under Examinations › Regulations."));
    }

    @Transactional(readOnly = true)
    public ExamSettings settings() {
        return settings.findFirstByOrderByIdAsc().orElseGet(ExamSettings::new);
    }

    @Transactional
    public SettingsView updateSettings(SettingsView req) {
        ExamSettings s = settings.findFirstByOrderByIdAsc().orElseGet(ExamSettings::new);
        if (req.useDepartmentBoard() != null) s.setUseDepartmentBoard(req.useDepartmentBoard());
        if (req.minAttendancePercent() != null) {
            if (req.minAttendancePercent().signum() < 0 || req.minAttendancePercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessRuleException("INVALID_THRESHOLD", "The attendance threshold must be between 0 and 100%");
            }
            s.setMinAttendancePercent(req.minAttendancePercent().setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (req.eligibilityRequiresCa() != null) s.setEligibilityRequiresCa(req.eligibilityRequiresCa());
        if (req.eligibilityRequiresClearance() != null) s.setEligibilityRequiresClearance(req.eligibilityRequiresClearance());
        if (req.attendanceAlertAfterSessions() != null) {
            if (req.attendanceAlertAfterSessions() < 1 || req.attendanceAlertAfterSessions() > 50) {
                throw new BusinessRuleException("INVALID_SETTING", "Warn students after between 1 and 50 sessions");
            }
            s.setAttendanceAlertAfterSessions(req.attendanceAlertAfterSessions().shortValue());
        }
        settings.save(s);
        SettingsView out = settingsView(s);
        audit.record("EXAM_SETTINGS_UPDATED", "ExamSettings", s.getId(), Map.of("useDepartmentBoard", out.useDepartmentBoard(),
                "minAttendancePercent", out.minAttendancePercent(), "eligibilityRequiresCa", out.eligibilityRequiresCa(),
                "eligibilityRequiresClearance", out.eligibilityRequiresClearance(), "attendanceAlertAfterSessions", out.attendanceAlertAfterSessions()));
        return out;
    }

    public static SettingsView settingsView(ExamSettings s) {
        return new SettingsView(s.isUseDepartmentBoard(), s.getMinAttendancePercent(), s.isEligibilityRequiresCa(), s.isEligibilityRequiresClearance(),
                (int) s.getAttendanceAlertAfterSessions());
    }

    boolean inUse(RegulationSet rs) {
        return rs.getId() != null && (results.countByRegulationSetId(rs.getId()) > 0 || sheets.countByRegulationSetId(rs.getId()) > 0
                || offerings.countByRegulationSetId(rs.getId()) > 0);
    }

    private void apply(RegulationSet rs, RegulationRequest req) {
        if (req.levelTo() < req.levelFrom()) throw new BusinessRuleException("INVALID_LEVELS", "The highest level must not be below the lowest");
        if (req.caWeight().add(req.seWeight()).compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessRuleException("INVALID_WEIGHTS", "CA and SE weights must add up to 100");
        }
        checkBands(req.gradeBands());
        for (ClassificationDto c : req.classifications()) {
            if (c.maxGpa().compareTo(c.minGpa()) < 0) throw new BusinessRuleException("INVALID_CLASSIFICATION", c.name() + ": the range is reversed");
        }
        AcademicYear y = years.findById(req.effectiveYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.effectiveYearId()));
        boolean clash = sets.findAll().stream().anyMatch(o -> !o.getId().equals(rs.getId()) && o.getAwardFamily() == req.awardFamily()
                && o.getEffectiveYear().getId().equals(y.getId()) && o.getLevelFrom() <= req.levelTo() && req.levelFrom() <= o.getLevelTo());
        if (clash) {
            throw new BusinessRuleException("OVERLAPPING_REGULATIONS", "Another set already covers some of " + req.awardFamily() + " levels "
                    + req.levelFrom() + "–" + req.levelTo() + " from " + y.getCode());
        }
        if (req.suppMaxGrade() != null && !req.suppMaxGrade().isBlank()
                && req.gradeBands().stream().noneMatch(b -> b.grade().equalsIgnoreCase(req.suppMaxGrade().trim()))) {
            throw new BusinessRuleException("UNKNOWN_GRADE", "The supplementary cap " + req.suppMaxGrade() + " is not a grade on the scale");
        }
        rs.setName(req.name().trim());
        rs.setAwardFamily(req.awardFamily());
        rs.setLevelFrom(req.levelFrom());
        rs.setLevelTo(req.levelTo());
        rs.setEffectiveYear(y);
        rs.setCaWeight(req.caWeight());
        rs.setSeWeight(req.seWeight());
        rs.setCaPassPercent(req.caPassPercent());
        rs.setSePassPercent(req.sePassPercent());
        rs.setGpaDecimals(req.gpaDecimals());
        rs.setGpaRounding(req.gpaRounding());
        rs.setSuppMaxFailedPercent(req.suppMaxFailedPercent());
        rs.setSuppMaxGrade(req.suppMaxGrade() == null || req.suppMaxGrade().isBlank() ? null : req.suppMaxGrade().trim().toUpperCase());
        rs.setDiscontinueGpaBelow(req.discontinueGpaBelow());
        rs.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        rs.getGradeBands().clear();
        rs.getClassifications().clear();
        if (rs.getId() != null) sets.flush(); // remove the old lines before inserting their replacements (unique grade per set)
        for (GradeBandDto b : req.gradeBands()) {
            GradeBand g = new GradeBand();
            g.setRegulationSet(rs);
            g.setGrade(b.grade().trim().toUpperCase());
            g.setMinMark(b.minMark());
            g.setMaxMark(b.maxMark());
            g.setPoints(b.points());
            g.setRemark(b.remark() == null || b.remark().isBlank() ? null : b.remark().trim());
            g.setPassing(b.passing());
            rs.getGradeBands().add(g);
        }
        for (ClassificationDto c : req.classifications()) {
            ClassificationBand k = new ClassificationBand();
            k.setRegulationSet(rs);
            k.setName(c.name().trim());
            k.setMinGpa(c.minGpa());
            k.setMaxGpa(c.maxGpa());
            rs.getClassifications().add(k);
        }
        rs.getGradeBands().sort(Comparator.comparing(GradeBand::getMinMark).reversed());
    }

    /** The scale must cover every whole mark from 0 to 100 exactly once, with unique grades and at least one pass. */
    static void checkBands(List<GradeBandDto> bands) {
        List<GradeBandDto> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(GradeBandDto::minMark));
        Set<String> grades = new HashSet<>();
        int expected = 0;
        for (GradeBandDto b : sorted) {
            if (!grades.add(b.grade().trim().toUpperCase())) throw new BusinessRuleException("INVALID_SCALE", "Grade " + b.grade() + " appears twice");
            if (b.maxMark() < b.minMark()) throw new BusinessRuleException("INVALID_SCALE", "Grade " + b.grade() + ": the range is reversed");
            if (b.minMark() != expected) {
                throw new BusinessRuleException("INVALID_SCALE", b.minMark() > expected
                        ? "No grade covers marks " + expected + "–" + (b.minMark() - 1)
                        : "Grade " + b.grade() + " overlaps the grade below it at " + b.minMark());
            }
            expected = b.maxMark() + 1;
        }
        if (expected != 101) throw new BusinessRuleException("INVALID_SCALE", "No grade covers marks " + expected + "–100");
        if (bands.stream().noneMatch(GradeBandDto::passing)) throw new BusinessRuleException("INVALID_SCALE", "At least one grade must be a pass");
    }

    RegulationSet require(Long id) {
        return sets.findById(id).orElseThrow(() -> new NotFoundException("Regulations", id));
    }

    RegulationView view(RegulationSet rs) {
        return new RegulationView(rs.getId(), rs.getName(), rs.getAwardFamily().name(), rs.getLevelFrom(), rs.getLevelTo(),
                rs.getEffectiveYear().getId(), rs.getEffectiveYear().getCode(), rs.getCaWeight(), rs.getSeWeight(), rs.getCaPassPercent(),
                rs.getSePassPercent(), Grading.caPassMark(rs), Grading.sePassMark(rs), rs.getGpaDecimals(), rs.getGpaRounding().name(),
                rs.getSuppMaxFailedPercent(), rs.getSuppMaxGrade(), rs.getDiscontinueGpaBelow(), rs.getNotes(), rs.getCreatedByName(), inUse(rs),
                bands(rs), rs.getClassifications().stream().map(c -> new ClassificationDto(c.getName(), c.getMinGpa(), c.getMaxGpa())).toList());
    }

    static List<GradeBandDto> bands(RegulationSet rs) {
        return rs.getGradeBands().stream().map(b -> new GradeBandDto(b.getGrade(), b.getMinMark(), b.getMaxMark(), b.getPoints(), b.getRemark(),
                b.isPassing())).toList();
    }
}
