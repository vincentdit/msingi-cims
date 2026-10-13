package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.ComponentDto;
import tz.co.msingi.cims.exams.ExamDtos.OfferingSummary;
import tz.co.msingi.cims.exams.ExamDtos.OfferingView;
import tz.co.msingi.cims.exams.ExamDtos.Permissions;
import tz.co.msingi.cims.exams.ExamDtos.Person;
import tz.co.msingi.cims.exams.ExamDtos.ScoreCell;
import tz.co.msingi.cims.exams.ExamDtos.ScoreEntry;
import tz.co.msingi.cims.exams.ExamDtos.SetupResult;
import tz.co.msingi.cims.exams.ExamDtos.StaffOption;
import tz.co.msingi.cims.exams.ExamDtos.Stats;
import tz.co.msingi.cims.exams.ExamDtos.StudentRow;
import tz.co.msingi.cims.exams.ExamDtos.UploadError;
import tz.co.msingi.cims.exams.ExamDtos.UploadResult;
import tz.co.msingi.cims.exams.ExamRepositories.AssessmentScoreRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.ModuleResult.Remark;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Module offerings and their results up to department approval (FR-TCH-01, FR-EXM-01/02/03/05, BP-08 steps 1-5): set up from
 * approved semester registrations, lecturers allocated, assessment plan, marks on screen or by Excel, live grading, submission,
 * moderation by the Head of Department (with grade distribution and anomaly flags) and the Department Board.
 */
@Service
@RequiredArgsConstructor
public class OfferingService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String TEMPLATE_SHEET = "_cims";
    private static final SemesterRegistration.Status APPROVED = SemesterRegistration.Status.APPROVED;

    private final ModuleOfferingRepository offerings;
    private final AssessmentScoreRepository scores;
    private final ModuleResultRepository results;
    private final ExamRegistrationQueries registrations;
    private final SemesterRepository semesters;
    private final RegulationService regulations;
    private final UserAccountRepository users;
    private final ExamAccess access;
    private final AuditService audit;
    private final tz.co.msingi.cims.teaching.TeachingRepositories.ExamEligibilityRepository eligibility;

    // ------------------------------------------------------------------ set-up and allocation

    /** Creates an offering for every module students are registered for in the semester, with the default CA/SE plan. */
    @Transactional
    public SetupResult setup(Long semesterId) {
        access.require("set up module offerings", ExamAccess.ALLOCATORS);
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        int created = 0;
        List<String> skipped = new ArrayList<>();
        for (Long moduleId : registrations.modulesTaken(semesterId, APPROVED)) {
            if (offerings.findBySemesterIdAndModuleId(semesterId, moduleId).isPresent()) continue;
            List<ModuleRegistration> regs = registrations.students(semesterId, moduleId, APPROVED);
            if (regs.isEmpty()) continue;
            Module m = regs.get(0).getModule();
            // a Head of Department limited to a department sets up only that department's modules
            if (!access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC) && !access.moderates(m)) continue;
            if (m.getAssessmentType() == Module.AssessmentType.COMPETENCE) {
                skipped.add(m.getCode() + ": competence-based (NVA) assessment is recorded separately");
                continue;
            }
            // The plan follows the regulations of the programme most students of the module are on; each student is then graded
            // on their own programme's scale, so a shared module never mixes grading scales within one GPA.
            Programme p = regs.stream().map(r -> r.getRegistration().getStudent().getProgramme())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            if (p.getAwardFamily() != Programme.AwardFamily.NTA) {
                skipped.add(m.getCode() + ": NVA programmes are assessed by competence, recorded separately");
                continue;
            }
            RegulationSet rs;
            try {
                rs = regulations.forLevel(p.getAwardFamily(), p.getAwardLevel(), sem.getAcademicYear());
            } catch (BusinessRuleException e) {
                skipped.add(m.getCode() + ": " + e.getMessage());
                continue;
            }
            ModuleOffering o = new ModuleOffering();
            o.setSemester(sem);
            o.setModule(m);
            o.setRegulationSet(rs);
            if (rs.getCaWeight().signum() > 0) o.getComponents().add(component(o, "Coursework", AssessmentComponent.Category.CA, rs.getCaWeight(), 0));
            if (rs.getSeWeight().signum() > 0) o.getComponents().add(component(o, "Semester examination", AssessmentComponent.Category.SE, rs.getSeWeight(), 1));
            offerings.save(o);
            created++;
        }
        audit.record("MODULE_OFFERINGS_SET_UP", "Semester", semesterId, Map.of("created", created, "skipped", skipped.size()));
        return new SetupResult(created, skipped);
    }

    @Transactional(readOnly = true)
    public List<StaffOption> staffOptions() {
        access.require("list staff", ExamAccess.ALLOCATORS);
        Set<Role> teaching = Set.of(Role.LECTURER, Role.ASSESSOR, Role.HEAD_OF_DEPARTMENT, Role.PROGRAMME_COORDINATOR, Role.EXAMINATIONS_OFFICER,
                Role.DEPUTY_PRINCIPAL_ACADEMIC);
        return users.findAll().stream()
                .filter(u -> u.getUserType() == UserAccount.UserType.STAFF && u.getStatus() == UserAccount.Status.ACTIVE)
                .filter(u -> u.getRoles().stream().anyMatch(r -> teaching.contains(r.getRole())))
                .sorted(Comparator.comparing(UserAccount::getFullName))
                .map(u -> new StaffOption(u.getId(), u.getFullName(), u.getRoles().stream().map(r -> r.getRole().name()).sorted().toList()))
                .toList();
    }

    @Transactional
    public OfferingView assign(Long id, List<Long> userIds) {
        ModuleOffering o = require(id);
        if (!access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC) && !access.moderates(o.getModule())) {
            throw new AccessDeniedException("Only the Examinations Officer or the module's Head of Department allocates lecturers");
        }
        Set<Long> wanted = new HashSet<>(userIds);
        List<UserAccount> found = users.findAllById(wanted);
        if (found.size() != wanted.size() || found.stream().anyMatch(u -> u.getUserType() != UserAccount.UserType.STAFF)) {
            throw new BusinessRuleException("UNKNOWN_STAFF", "Choose staff members of this institution");
        }
        o.getStaff().removeIf(s -> !wanted.contains(s.getUserId()));
        Set<Long> have = o.getStaff().stream().map(OfferingStaff::getUserId).collect(Collectors.toSet());
        for (Long uid : wanted) {
            if (have.contains(uid)) continue;
            OfferingStaff s = new OfferingStaff();
            s.setOffering(o);
            s.setUserId(uid);
            o.getStaff().add(s);
        }
        audit.record("LECTURERS_ALLOCATED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(),
                "staff", found.stream().map(UserAccount::getFullName).sorted().toList()));
        return view(o);
    }

    /** The Examinations Officer opens (and closes) marks entry for a semester (BP-08 step 2). */
    @Transactional
    public void marksWindow(Long semesterId, LocalDate opens, LocalDate closes) {
        access.require("set the marks entry window", ExamAccess.EXAMS_OFFICE);
        Semester s = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        if (opens != null && closes != null && closes.isBefore(opens)) throw new BusinessRuleException("INVALID_DATES", "Marks entry must close after it opens");
        s.setMarksEntryOpens(opens);
        s.setMarksEntryCloses(closes);
        audit.record("MARKS_WINDOW_SET", "Semester", s.getId(), Map.of("opens", String.valueOf(opens), "closes", String.valueOf(closes)));
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<OfferingSummary> list(Long semesterId, boolean mineOnly) {
        Long me = access.me();
        List<ModuleOffering> all = offerings.findBySemesterIdOrderByIdAsc(semesterId);
        Map<Long, String> names = staffNames(all.stream().flatMap(o -> o.getStaff().stream().map(OfferingStaff::getUserId)).collect(Collectors.toSet()));
        return all.stream()
                .filter(o -> isMine(o, me) || (!mineOnly && access.follows(o.getModule())))
                .sorted(Comparator.comparing((ModuleOffering o) -> o.getModule().getCode()))
                .map(o -> {
                    List<ModuleRegistration> regs = registrations.students(semesterId, o.getModule().getId(), APPROVED);
                    long complete = results.findByOfferingId(o.getId()).stream().filter(r -> r.getRemark() != Remark.INCOMPLETE).count();
                    return new OfferingSummary(o.getId(), semesterId, o.getSemester().getName(), o.getModule().getId(), o.getModule().getCode(),
                            o.getModule().getTitle(), o.getModule().getCredits(), o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(),
                            o.getStatus().name(), regs.size(), (int) complete, people(o, names), o.getRegulationSet().getName(), o.getReturnedNote(), isMine(o, me));
                }).toList();
    }

    @Transactional(readOnly = true)
    public OfferingView get(Long id) {
        ModuleOffering o = require(id);
        requireRead(o);
        return view(o);
    }

    // ------------------------------------------------------------------ plan and marks

    @Transactional
    public OfferingView savePlan(Long id, List<ComponentDto> plan) {
        ModuleOffering o = require(id);
        if (!canEditPlan(o)) throw new AccessDeniedException("Only the module's lecturer changes the assessment plan, before submission");
        RegulationSet rs = o.getRegulationSet();
        BigDecimal ca = sum(plan, AssessmentComponent.Category.CA), se = sum(plan, AssessmentComponent.Category.SE);
        if (ca.compareTo(rs.getCaWeight()) != 0 || se.compareTo(rs.getSeWeight()) != 0) {
            throw new BusinessRuleException("PLAN_WEIGHTS", "Under " + rs.getName() + " the CA items must add up to " + Grading.plain(rs.getCaWeight())
                    + " and the SE items to " + Grading.plain(rs.getSeWeight()) + " (now " + Grading.plain(ca) + " and " + Grading.plain(se) + ")");
        }
        Map<Long, AssessmentComponent> existing = o.getComponents().stream().collect(Collectors.toMap(AssessmentComponent::getId, Function.identity()));
        Set<Long> kept = plan.stream().map(ComponentDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (Long kid : kept) if (!existing.containsKey(kid)) throw new BusinessRuleException("UNKNOWN_COMPONENT", "That assessment item is not part of this module");
        for (AssessmentComponent c : existing.values()) {
            if (!kept.contains(c.getId()) && scores.countByComponentId(c.getId()) > 0) {
                throw new BusinessRuleException("COMPONENT_HAS_MARKS", c.getName() + " already has marks. Clear them before removing it.");
            }
        }
        o.getComponents().removeIf(c -> !kept.contains(c.getId()));
        short order = 0;
        List<ComponentDto> ordered = new ArrayList<>(plan);
        ordered.sort(Comparator.comparing(ComponentDto::category)); // coursework items first, then the examination (stable)
        for (ComponentDto d : ordered) {
            AssessmentComponent c = d.id() == null ? component(o, d.name(), d.category(), d.weight(), order) : existing.get(d.id());
            if (d.id() != null && c.getCategory() != d.category() && scores.countByComponentId(c.getId()) > 0) {
                throw new BusinessRuleException("COMPONENT_HAS_MARKS", c.getName() + " already has marks: its category cannot change");
            }
            c.setName(d.name().trim());
            c.setCategory(d.category());
            c.setWeight(d.weight());
            c.setMaxScore(d.maxScore());
            c.setSortOrder(order++);
            if (d.id() == null) o.getComponents().add(c);
        }
        o.getComponents().sort(Comparator.comparing(AssessmentComponent::getSortOrder));
        offerings.saveAndFlush(o);
        recompute(o, null);
        audit.record("ASSESSMENT_PLAN_SAVED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(),
                "components", o.getComponents().stream().map(c -> c.getName() + " " + Grading.plain(c.getWeight())).toList()));
        return view(o);
    }

    @Transactional
    public OfferingView saveScores(Long id, List<ScoreEntry> entries) {
        ModuleOffering o = require(id);
        requireLecturer(o);
        Map<Long, AssessmentComponent> comps = o.getComponents().stream().collect(Collectors.toMap(AssessmentComponent::getId, Function.identity()));
        Set<Long> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED).stream().map(ModuleRegistration::getId)
                .collect(Collectors.toSet());
        Window w = window(o.getSemester());
        Map<Long, Boolean> eligible = eligibleByRegistration(o);
        for (ScoreEntry e : entries) {
            AssessmentComponent c = comps.get(e.componentId());
            if (c == null) throw new BusinessRuleException("UNKNOWN_COMPONENT", "That assessment item is not part of this module");
            if (!regs.contains(e.moduleRegistrationId())) throw new BusinessRuleException("NOT_IN_CLASS", "That student is not registered for this module");
            checkWindow(w, c);
            checkScore(c, e.score(), e.absent());
            if (c.getCategory() == AssessmentComponent.Category.SE && e.score() != null && Boolean.FALSE.equals(eligible.get(e.moduleRegistrationId()))) {
                throw new BusinessRuleException("NOT_ELIGIBLE_FOR_EXAM", "A student on the list is not eligible to sit this examination, so has no "
                        + c.getName() + " mark. See the eligibility list.");
            }
        }
        Map<String, AssessmentScore> current = scores.findByOffering(o.getId()).stream()
                .collect(Collectors.toMap(s -> key(s.getComponent().getId(), s.getModuleRegistrationId()), Function.identity()));
        Set<Long> touched = new HashSet<>();
        for (ScoreEntry e : entries) {
            AssessmentScore s = current.get(key(e.componentId(), e.moduleRegistrationId()));
            if (e.score() == null && !e.absent()) {
                if (s != null) scores.delete(s);
            } else {
                if (s == null) {
                    s = new AssessmentScore();
                    s.setComponent(comps.get(e.componentId()));
                    s.setModuleRegistrationId(e.moduleRegistrationId());
                }
                s.setScore(e.absent() ? null : e.score());
                s.setAbsent(e.absent());
                s.setEnteredByName(CurrentUser.name());
                s.setEnteredAt(OffsetDateTime.now());
                scores.save(s);
            }
            touched.add(e.moduleRegistrationId());
        }
        scores.flush();
        recompute(o, touched);
        audit.record("MARKS_ENTERED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "entries", entries.size(),
                "students", touched.size()));
        return view(o);
    }

    /**
     * Marks produced outside the marks sheet (e.g. industrial practical training, FR-IPT-03), out of 100 per student: every
     * assessment item gets the same share of its maximum, so the module total equals the mark. The caller checks who may do this;
     * results already submitted cannot change. Returns the number of students given marks.
     */
    @Transactional
    public int transferMarks(Long offeringId, Map<Long, BigDecimal> marks, String source) {
        ModuleOffering o = require(offeringId);
        if (o.getStatus() != ModuleOffering.Status.DRAFT) {
            throw new BusinessRuleException("RESULTS_SUBMITTED", "The results of " + o.getModule().getCode() + " have been submitted and can change only if returned");
        }
        Set<Long> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED).stream().map(ModuleRegistration::getId)
                .collect(Collectors.toSet());
        Map<String, AssessmentScore> current = scores.findByOffering(o.getId()).stream()
                .collect(Collectors.toMap(sc -> key(sc.getComponent().getId(), sc.getModuleRegistrationId()), Function.identity()));
        Set<Long> touched = new HashSet<>();
        for (Map.Entry<Long, BigDecimal> e : marks.entrySet()) {
            if (!regs.contains(e.getKey())) continue;
            for (AssessmentComponent c : o.getComponents()) {
                AssessmentScore sc = current.get(key(c.getId(), e.getKey()));
                if (sc == null) {
                    sc = new AssessmentScore();
                    sc.setComponent(c);
                    sc.setModuleRegistrationId(e.getKey());
                }
                sc.setScore(e.getValue().multiply(c.getMaxScore()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                sc.setAbsent(false);
                sc.setEnteredByName(CurrentUser.name() + " (" + source + ")");
                sc.setEnteredAt(OffsetDateTime.now());
                scores.save(sc);
            }
            touched.add(e.getKey());
        }
        scores.flush();
        recompute(o, touched);
        audit.record("MARKS_TRANSFERRED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "students", touched.size(), "source", source));
        return touched.size();
    }

    /** An Excel sheet with the class list and one column per assessment item; a hidden sheet identifies the module and items. */
    @Transactional(readOnly = true)
    public byte[] template(Long id) {
        ModuleOffering o = require(id);
        requireRead(o);
        List<ModuleRegistration> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED);
        Map<String, AssessmentScore> current = scores.findByOffering(o.getId()).stream()
                .collect(Collectors.toMap(s -> key(s.getComponent().getId(), s.getModuleRegistrationId()), Function.identity()));
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(safeSheetName(o.getModule().getCode() + " marks"));
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row h = sh.createRow(0);
            String[] fixed = {"Registration no.", "Name", "Programme"};
            for (int i = 0; i < fixed.length; i++) cell(h, i, fixed[i], head);
            for (int i = 0; i < o.getComponents().size(); i++) {
                AssessmentComponent c = o.getComponents().get(i);
                cell(h, 3 + i, c.getName() + " (" + c.getCategory() + ", out of " + Grading.plain(c.getMaxScore()) + ")", head);
            }
            int r = 1;
            for (ModuleRegistration m : regs) {
                Row row = sh.createRow(r++);
                Student s = m.getRegistration().getStudent();
                row.createCell(0).setCellValue(s.getRegistrationNo());
                row.createCell(1).setCellValue(s.fullName());
                row.createCell(2).setCellValue(s.getProgramme().getCode());
                for (int i = 0; i < o.getComponents().size(); i++) {
                    AssessmentScore sc = current.get(key(o.getComponents().get(i).getId(), m.getId()));
                    if (sc == null) continue;
                    if (sc.isAbsent()) row.createCell(3 + i).setCellValue("ABS");
                    else row.createCell(3 + i).setCellValue(sc.getScore().doubleValue());
                }
            }
            sh.createFreezePane(3, 1);
            sh.setColumnWidth(0, 24 * 256);
            sh.setColumnWidth(1, 32 * 256);
            sh.setColumnWidth(2, 12 * 256);
            for (int i = 0; i < o.getComponents().size(); i++) sh.setColumnWidth(3 + i, 26 * 256);
            Row note = sh.createRow(r + 1);
            note.createCell(0).setCellValue("Enter marks out of the number shown. Type ABS for absent. Leave a cell empty to keep it unchanged.");
            Sheet meta = wb.createSheet(TEMPLATE_SHEET);
            Row m0 = meta.createRow(0);
            m0.createCell(0).setCellValue("offering");
            m0.createCell(1).setCellValue(o.getId());
            Row m1 = meta.createRow(1);
            m1.createCell(0).setCellValue("components");
            for (int i = 0; i < o.getComponents().size(); i++) m1.createCell(1 + i).setCellValue(o.getComponents().get(i).getId());
            wb.setSheetVisibility(wb.getSheetIndex(meta), SheetVisibility.VERY_HIDDEN);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the marks template", e);
        }
    }

    /** Reads a filled template. All rows are checked first; nothing is saved unless the whole file is valid. */
    @Transactional
    public UploadResult upload(Long id, byte[] file) {
        ModuleOffering o = require(id);
        Map<Long, Boolean> eligibleNow = eligibleByRegistration(o);
        requireLecturer(o);
        List<UploadError> errors = new ArrayList<>();
        List<ScoreEntry> entries = new ArrayList<>();
        Map<String, ModuleRegistration> byRegNo = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED).stream()
                .collect(Collectors.toMap(m -> m.getRegistration().getStudent().getRegistrationNo().toUpperCase(), Function.identity()));
        Map<Long, AssessmentComponent> comps = o.getComponents().stream().collect(Collectors.toMap(AssessmentComponent::getId, Function.identity()));
        Window w = window(o.getSemester());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            Sheet meta = wb.getSheet(TEMPLATE_SHEET);
            if (meta == null || meta.getRow(0) == null || (long) meta.getRow(0).getCell(1).getNumericCellValue() != o.getId()) {
                throw new BusinessRuleException("WRONG_TEMPLATE", "This is not the marks template of " + o.getModule().getCode()
                        + " " + o.getSemester().getName() + ". Download the template from this page and fill it in.");
            }
            Row ids = meta.getRow(1);
            List<Long> columns = new ArrayList<>();
            for (int i = 1; i < ids.getLastCellNum(); i++) columns.add((long) ids.getCell(i).getNumericCellValue());
            for (Long cid : columns) {
                if (!comps.containsKey(cid)) throw new BusinessRuleException("PLAN_CHANGED", "The assessment plan changed after this template was downloaded. Download it again.");
            }
            Sheet sh = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Set<String> seen = new HashSet<>();
            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String regNo = fmt.formatCellValue(row.getCell(0)).trim();
                if (regNo.isEmpty() || regNo.startsWith("Enter marks")) continue;
                ModuleRegistration m = byRegNo.get(regNo.toUpperCase());
                if (m == null) {
                    errors.add(new UploadError(r + 1, regNo + " is not registered for " + o.getModule().getCode()));
                    continue;
                }
                if (!seen.add(regNo.toUpperCase())) {
                    errors.add(new UploadError(r + 1, regNo + " appears more than once"));
                    continue;
                }
                for (int i = 0; i < columns.size(); i++) {
                    AssessmentComponent c = comps.get(columns.get(i));
                    Cell cell = row.getCell(3 + i);
                    if (cell == null || cell.getCellType() == CellType.BLANK) continue;
                    String text = fmt.formatCellValue(cell).trim();
                    if (text.isEmpty()) continue;
                    boolean absent = text.equalsIgnoreCase("ABS") || text.equalsIgnoreCase("AB") || text.equalsIgnoreCase("ABSENT");
                    BigDecimal score = null;
                    if (!absent) {
                        try {
                            score = cell.getCellType() == CellType.NUMERIC ? BigDecimal.valueOf(cell.getNumericCellValue()) : new BigDecimal(text);
                        } catch (NumberFormatException ex) {
                            errors.add(new UploadError(r + 1, regNo + ", " + c.getName() + ": \"" + text + "\" is not a mark (use a number or ABS)"));
                            continue;
                        }
                        score = score.setScale(2, RoundingMode.HALF_UP);
                    }
                    try {
                        checkWindow(w, c);
                        checkScore(c, score, absent);
                    } catch (BusinessRuleException ex) {
                        errors.add(new UploadError(r + 1, regNo + ", " + c.getName() + ": " + ex.getMessage()));
                        continue;
                    }
                    if (c.getCategory() == AssessmentComponent.Category.SE && score != null && Boolean.FALSE.equals(eligibleNow.get(m.getId()))) {
                        errors.add(new UploadError(r + 1, regNo + ", " + c.getName() + ": not eligible to sit the examination (leave blank or ABS)"));
                        continue;
                    }
                    entries.add(new ScoreEntry(m.getId(), c.getId(), score, absent));
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessRuleException b) throw b;
            throw new BusinessRuleException("UNREADABLE_FILE", "The file could not be read as an Excel workbook (.xlsx).");
        }
        if (!errors.isEmpty()) return new UploadResult(0, errors);
        if (!entries.isEmpty()) saveScores(id, entries);
        audit.record("MARKS_UPLOADED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "entries", entries.size()));
        return new UploadResult(entries.size(), List.of());
    }

    // ------------------------------------------------------------------ approval route (BP-08 steps 3-5)

    @Transactional
    public OfferingView submit(Long id) {
        ModuleOffering o = require(id);
        requireLecturer(o);
        List<ModuleRegistration> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED);
        if (regs.isEmpty()) throw new BusinessRuleException("NO_STUDENTS", "No students are registered for this module");
        recompute(o, null);
        long incomplete = results.findByOfferingId(o.getId()).stream().filter(r -> r.getRemark() == Remark.INCOMPLETE).count();
        if (incomplete > 0) {
            throw new BusinessRuleException("MARKS_INCOMPLETE", incomplete + " student" + (incomplete == 1 ? " has" : "s have")
                    + " missing marks. Enter every mark, or ABS for a student who missed the examination.");
        }
        o.setStatus(ModuleOffering.Status.SUBMITTED);
        o.setSubmittedByName(CurrentUser.name());
        o.setSubmittedAt(OffsetDateTime.now());
        audit.record("RESULTS_SUBMITTED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "students", regs.size()));
        return view(o);
    }

    @Transactional
    public OfferingView hodApprove(Long id) {
        ModuleOffering o = require(id);
        if (o.getStatus() != ModuleOffering.Status.SUBMITTED) throw new BusinessRuleException("WRONG_STAGE", "Only submitted results can be moderated");
        if (!access.moderates(o.getModule())) throw new AccessDeniedException("Only the module's Head of Department approves its results");
        o.setStatus(regulations.settings().isUseDepartmentBoard() ? ModuleOffering.Status.HOD_APPROVED : ModuleOffering.Status.BOARD_APPROVED);
        o.setHodByName(CurrentUser.name());
        o.setHodAt(OffsetDateTime.now());
        o.setReturnedNote(null);
        audit.record("RESULTS_HOD_APPROVED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode()));
        return view(o);
    }

    @Transactional
    public OfferingView boardApprove(Long id) {
        ModuleOffering o = require(id);
        if (o.getStatus() != ModuleOffering.Status.HOD_APPROVED) {
            throw new BusinessRuleException("WRONG_STAGE", "The Head of Department approves the results before the Department Board");
        }
        if (!canBoard(o)) throw new AccessDeniedException("The Head of Department or Examinations Officer records the Department Board's approval");
        o.setStatus(ModuleOffering.Status.BOARD_APPROVED);
        o.setBoardByName(CurrentUser.name());
        o.setBoardAt(OffsetDateTime.now());
        audit.record("RESULTS_BOARD_APPROVED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode()));
        return view(o);
    }

    @Transactional
    public OfferingView returnToLecturer(Long id, String note) {
        ModuleOffering o = require(id);
        if (!canReturn(o)) throw new AccessDeniedException("Not allowed to return these results");
        if (results.findByOfferingId(o.getId()).stream().anyMatch(ModuleResult::isLocked)) {
            throw new BusinessRuleException("RESULTS_LOCKED", "These results are confirmed by the Governing Board and can only change by amendment");
        }
        o.setStatus(ModuleOffering.Status.DRAFT);
        o.setReturnedNote(note.trim());
        o.setReturnedByName(CurrentUser.name());
        o.setReturnedAt(OffsetDateTime.now());
        audit.record("RESULTS_RETURNED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "note", note.trim()));
        return view(o);
    }

    /** Whether an offering's results have passed every department stage the institution uses. */
    static boolean departmentApproved(ModuleOffering o) {
        return o.getStatus() == ModuleOffering.Status.BOARD_APPROVED;
    }

    // ------------------------------------------------------------------ computation

    /** Re-grades the given module registrations (all when null) from their scores. Locked results are left alone. */
    void recompute(ModuleOffering o, Collection<Long> only) {
        Map<Long, RegulationSet> byProgramme = new HashMap<>();
        List<ModuleRegistration> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED);
        Map<Long, Map<Long, Grading.Score>> byReg = new HashMap<>();
        for (AssessmentScore s : scores.findByOffering(o.getId())) {
            byReg.computeIfAbsent(s.getModuleRegistrationId(), k -> new HashMap<>()).put(s.getComponent().getId(), new Grading.Score(s.getScore(), s.isAbsent()));
        }
        Map<Long, ModuleResult> existing = results.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(ModuleResult::getModuleRegistrationId, Function.identity()));
        for (ModuleRegistration m : regs) {
            if (only != null && !only.contains(m.getId())) continue;
            ModuleResult r = existing.get(m.getId());
            if (r != null && r.isLocked()) continue;
            Programme prog = m.getRegistration().getStudent().getProgramme();
            RegulationSet rs = byProgramme.computeIfAbsent(prog.getId(), k -> prog.getAwardFamily() == Programme.AwardFamily.NTA
                    ? regulations.forLevel(prog.getAwardFamily(), prog.getAwardLevel(), o.getSemester().getAcademicYear()) : o.getRegulationSet());
            Grading.ModuleOutcome out = Grading.module(rs, o.getComponents(), byReg.getOrDefault(m.getId(), Map.of()));
            if (r == null) {
                r = new ModuleResult();
                r.setModuleRegistrationId(m.getId());
                r.setOfferingId(o.getId());
                r.setStudentId(m.getStudentId());
            }
            r.setRegulationSetId(rs.getId());
            r.setCredits(m.getCredits());
            r.setCaTotal(out.caTotal());
            r.setSeTotal(out.seTotal());
            r.setTotal(out.total());
            r.setGrade(out.grade());
            r.setGradePoints(out.points());
            r.setRemark(out.remark());
            r.setFailReason(out.failReason());
            r.setComputedAt(OffsetDateTime.now());
            results.save(r);
        }
    }

    // ------------------------------------------------------------------ view

    OfferingView view(ModuleOffering o) {
        RegulationSet rs = o.getRegulationSet();
        List<ModuleRegistration> regs = registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED);
        Map<Long, Map<Long, ScoreCell>> cells = new HashMap<>();
        for (AssessmentScore s : scores.findByOffering(o.getId())) {
            cells.computeIfAbsent(s.getModuleRegistrationId(), k -> new LinkedHashMap<>()).put(s.getComponent().getId(), new ScoreCell(s.getScore(), s.isAbsent()));
        }
        Map<Long, ModuleResult> res = results.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(ModuleResult::getModuleRegistrationId, Function.identity()));
        List<StudentRow> rows = new ArrayList<>();
        Map<Long, Boolean> eligible = eligibleByRegistration(o);
        for (ModuleRegistration m : regs) {
            Student s = m.getRegistration().getStudent();
            ModuleResult r = res.get(m.getId());
            Boolean el = eligible.get(m.getId());
            String reason = r == null ? "Marks missing" : r.getFailReason();
            if (Boolean.FALSE.equals(el) && (r == null || r.getRemark() == Remark.INCOMPLETE)) reason = "Not eligible to sit the examination";
            rows.add(new StudentRow(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), m.getCategory().name(),
                    cells.getOrDefault(m.getId(), Map.of()), r == null ? null : r.getCaTotal(), r == null ? null : r.getSeTotal(),
                    r == null ? null : r.getTotal(), r == null ? null : r.getGrade(), r == null ? null : r.getGradePoints(),
                    r == null ? Remark.INCOMPLETE.name() : r.getRemark().name(), reason, el));
        }
        Window w = window(o.getSemester());
        boolean lecturer = isMine(o, access.me()) && o.getStatus() == ModuleOffering.Status.DRAFT;
        Map<Long, String> names = staffNames(o.getStaff().stream().map(OfferingStaff::getUserId).collect(Collectors.toSet()));
        Permissions can = new Permissions(canEditPlan(o), lecturer && w.caOpen(), lecturer && w.seOpen(), lecturer && !rows.isEmpty(),
                o.getStatus() == ModuleOffering.Status.SUBMITTED && access.moderates(o.getModule()),
                o.getStatus() == ModuleOffering.Status.HOD_APPROVED && canBoard(o), canReturn(o),
                access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC) || access.moderates(o.getModule()));
        Semester sem = o.getSemester();
        return new OfferingView(o.getId(), sem.getId(), sem.getName(), sem.getAcademicYear().getCode(), o.getModule().getId(), o.getModule().getCode(),
                o.getModule().getTitle(), o.getModule().getCredits(), o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(),
                o.getStatus().name(), people(o, names), rs.getId(), rs.getName(), rs.getCaWeight(), rs.getSeWeight(), Grading.caPassMark(rs),
                Grading.sePassMark(rs), RegulationService.bands(rs), sem.getMarksEntryOpens(), sem.getMarksEntryCloses(), w.note(),
                regulations.settings().isUseDepartmentBoard(),
                o.getComponents().stream().map(c -> new ComponentDto(c.getId(), c.getName(), c.getCategory(), c.getWeight(), c.getMaxScore())).toList(),
                rows, stats(rs, rows), o.getSubmittedByName(), o.getSubmittedAt(), o.getHodByName(), o.getHodAt(), o.getBoardByName(), o.getBoardAt(),
                o.getReturnedNote(), o.getReturnedByName(), o.getReturnedAt(), can);
    }

    /** Grade distribution and the flags a moderator should look at (BP-08 step 4). */
    static Stats stats(RegulationSet rs, List<StudentRow> rows) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        rs.getGradeBands().stream().sorted(Comparator.comparing(GradeBand::getMinMark).reversed()).forEach(b -> dist.put(b.getGrade(), 0));
        List<StudentRow> graded = rows.stream().filter(r -> r.total() != null).toList();
        graded.forEach(r -> dist.merge(r.grade(), 1, Integer::sum));
        int passed = (int) rows.stream().filter(r -> Remark.PASS.name().equals(r.remark())).count();
        BigDecimal mean = graded.isEmpty() ? null : graded.stream().map(StudentRow::total).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(graded.size()), 1, RoundingMode.HALF_UP);
        BigDecimal passRate = graded.isEmpty() ? null : BigDecimal.valueOf(passed * 100L).divide(BigDecimal.valueOf(graded.size()), 1, RoundingMode.HALF_UP);
        List<String> flags = new ArrayList<>();
        long missing = rows.stream().filter(r -> Remark.INCOMPLETE.name().equals(r.remark())).count();
        long absent = rows.stream().filter(r -> Remark.ABSENT.name().equals(r.remark())).count();
        if (missing > 0) flags.add(missing + " of " + rows.size() + " students still have missing marks");
        if (absent > 0) flags.add(absent + " absent from the semester examination (special examination)");
        if (graded.size() >= 5) {
            if (passRate.compareTo(new BigDecimal("60")) < 0) flags.add("Low pass rate: " + Grading.plain(passRate) + "% passed");
            if (mean.compareTo(new BigDecimal("85")) > 0) flags.add("Unusually high average mark (" + Grading.plain(mean) + ")");
            if (graded.stream().map(StudentRow::total).distinct().count() == 1) flags.add("Every student has the same total");
            int passMin = rs.getGradeBands().stream().filter(GradeBand::isPassing).mapToInt(GradeBand::getMinMark).min().orElse(50);
            long atPass = graded.stream().filter(r -> r.total().intValue() == passMin).count();
            if (atPass >= 3 && atPass * 100 / graded.size() >= 15) flags.add(atPass + " students have exactly the pass mark of " + passMin);
            BigDecimal caAvg = percent(graded.stream().map(StudentRow::caTotal).toList(), rs.getCaWeight());
            BigDecimal seAvg = percent(graded.stream().map(StudentRow::seTotal).toList(), rs.getSeWeight());
            if (caAvg != null && seAvg != null && caAvg.subtract(seAvg).abs().compareTo(new BigDecimal("30")) > 0) {
                flags.add("Coursework and examination differ widely: CA average " + Grading.plain(caAvg) + "%, SE average " + Grading.plain(seAvg) + "%");
            }
        }
        return new Stats(rows.size(), graded.size(), passed, passRate, mean, dist, flags);
    }

    private static BigDecimal percent(List<BigDecimal> values, BigDecimal weight) {
        if (values.isEmpty() || weight.signum() == 0) return null;
        BigDecimal sum = values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.multiply(new BigDecimal("100")).divide(weight.multiply(BigDecimal.valueOf(values.size())), 1, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ marks entry window (FR-EXM-02)

    record Window(boolean caOpen, boolean seOpen, String note) {
    }

    static Window window(Semester s) {
        LocalDate today = LocalDate.now(ZONE);
        boolean closed = s.getMarksEntryCloses() != null && today.isAfter(s.getMarksEntryCloses());
        boolean seOpen = !closed && s.getMarksEntryOpens() != null && !today.isBefore(s.getMarksEntryOpens());
        String note;
        if (closed) note = "Marks entry closed on " + s.getMarksEntryCloses() + ". Ask the Examinations Officer to extend it.";
        else if (s.getMarksEntryOpens() == null) note = "Coursework marks can be entered. The Examinations Officer has not yet set when examination marks open.";
        else if (!seOpen) note = "Coursework marks can be entered. Examination marks open on " + s.getMarksEntryOpens() + ".";
        else note = "Marks entry is open" + (s.getMarksEntryCloses() == null ? "." : " until " + s.getMarksEntryCloses() + ".");
        return new Window(!closed, seOpen, note);
    }

    private static void checkWindow(Window w, AssessmentComponent c) {
        if (c.getCategory() == AssessmentComponent.Category.CA && !w.caOpen()) throw new BusinessRuleException("MARKS_ENTRY_CLOSED", w.note());
        if (c.getCategory() == AssessmentComponent.Category.SE && !w.seOpen()) throw new BusinessRuleException("SE_ENTRY_NOT_OPEN", w.note());
    }

    private static void checkScore(AssessmentComponent c, BigDecimal score, boolean absent) {
        if (absent && score != null) throw new BusinessRuleException("INVALID_SCORE", "A student marked absent has no score");
        if (score != null && (score.signum() < 0 || score.compareTo(c.getMaxScore()) > 0)) {
            throw new BusinessRuleException("INVALID_SCORE", Grading.plain(score) + " is outside 0–" + Grading.plain(c.getMaxScore()));
        }
    }

    // ------------------------------------------------------------------ access

    /** Eligibility from the latest list (FR-EXM-04); students with no entry have no list yet and may be given marks. */
    private Map<Long, Boolean> eligibleByRegistration(ModuleOffering o) {
        return eligibility.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(e -> e.getModuleRegistrationId(), e -> e.isEligible()));
    }

    private boolean isMine(ModuleOffering o, Long me) {
        return o.getStaff().stream().anyMatch(s -> s.getUserId().equals(me));
    }

    private boolean canEditPlan(ModuleOffering o) {
        return o.getStatus() == ModuleOffering.Status.DRAFT && (isMine(o, access.me()) || access.has(Role.EXAMINATIONS_OFFICER, Role.INSTITUTION_ADMIN)
                || access.moderates(o.getModule()));
    }

    private boolean canBoard(ModuleOffering o) {
        return access.moderates(o.getModule()) || access.has(Role.EXAMINATIONS_OFFICER, Role.INSTITUTION_ADMIN);
    }

    private boolean canReturn(ModuleOffering o) {
        return switch (o.getStatus()) {
            case SUBMITTED -> access.moderates(o.getModule());
            case HOD_APPROVED -> canBoard(o);
            case BOARD_APPROVED -> access.has(Role.EXAMINATIONS_OFFICER, Role.INSTITUTION_ADMIN);
            case DRAFT -> false;
        };
    }

    private void requireLecturer(ModuleOffering o) {
        if (!isMine(o, access.me())) throw new AccessDeniedException("Only the module's lecturers enter its marks");
        if (o.getStatus() != ModuleOffering.Status.DRAFT) {
            throw new BusinessRuleException("RESULTS_SUBMITTED", "These results have been submitted. They can change only if returned to you.");
        }
    }

    private void requireRead(ModuleOffering o) {
        if (isMine(o, access.me()) || access.follows(o.getModule())) return;
        throw new AccessDeniedException("Not your module");
    }

    // ------------------------------------------------------------------ helpers

    ModuleOffering require(Long id) {
        return offerings.findById(id).orElseThrow(() -> new NotFoundException("Module offering", id));
    }

    private Map<Long, String> staffNames(Set<Long> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static List<Person> people(ModuleOffering o, Map<Long, String> names) {
        return o.getStaff().stream().map(s -> new Person(s.getUserId(), names.getOrDefault(s.getUserId(), "—")))
                .sorted(Comparator.comparing(Person::fullName)).toList();
    }

    private static AssessmentComponent component(ModuleOffering o, String name, AssessmentComponent.Category cat, BigDecimal weight, int order) {
        AssessmentComponent c = new AssessmentComponent();
        c.setOffering(o);
        c.setName(name);
        c.setCategory(cat);
        c.setWeight(weight);
        c.setMaxScore(new BigDecimal("100"));
        c.setSortOrder((short) order);
        return c;
    }

    private static BigDecimal sum(List<ComponentDto> plan, AssessmentComponent.Category cat) {
        return plan.stream().filter(c -> c.category() == cat).map(ComponentDto::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String key(Long componentId, Long moduleRegistrationId) {
        return componentId + "/" + moduleRegistrationId;
    }

    private static void cell(Row r, int i, String v, CellStyle style) {
        Cell c = r.createCell(i);
        c.setCellValue(v);
        c.setCellStyle(style);
    }

    private static String safeSheetName(String s) {
        String clean = s.replaceAll("[\\\\/*?:\\[\\]]", " ");
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }
}
