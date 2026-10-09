package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.EligibleModule;
import tz.co.msingi.cims.exams.ExamDtos.ResitMarksView;
import tz.co.msingi.cims.exams.ExamDtos.ResitRow;
import tz.co.msingi.cims.exams.ExamDtos.ResitScore;
import tz.co.msingi.cims.exams.ExamDtos.SessionModule;
import tz.co.msingi.cims.exams.ExamDtos.SessionRequest;
import tz.co.msingi.cims.exams.ExamDtos.SessionView;
import tz.co.msingi.cims.exams.ExamDtos.StudentResits;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResitRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResitSessionRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResultSheetRepository;
import tz.co.msingi.cims.exams.ExamRepositories.SemesterResultRepository;
import tz.co.msingi.cims.exams.ModuleResult.Remark;
import tz.co.msingi.cims.exams.SemesterResult.Decision;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supplementary and special examinations (FR-EXM-08, BP-10). The Examinations Officer opens a session for a semester; students whose
 * published decision is supplementary or special examination register for their failed (supplementary) or missed (special) modules
 * and pay the fee by GePG; lecturers enter the resit examination for students who have paid; the Head of Department approves; the
 * class's RESIT result sheet then follows the usual committee and board route.
 */
@Service
@RequiredArgsConstructor
public class ResitService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    public static final String PURPOSE = "RESIT_FEES";

    private final ResitSessionRepository sessions;
    private final ResitRepository resits;
    private final SemesterResultRepository semesterResults;
    private final ResultSheetRepository sheets;
    private final ModuleResultRepository moduleResults;
    private final ModuleOfferingRepository offerings;
    private final SemesterRepository semesters;
    private final StudentRepository students;
    private final RegulationService regulations;
    private final BillingService billing;
    private final InvoiceRepository invoices;
    private final ExamAccess access;
    private final AuditService audit;

    // ------------------------------------------------------------------ the session (Examinations Officer)

    @Transactional
    public SessionView open(Long semesterId, SessionRequest req) {
        access.require("open supplementary and special examinations", ExamAccess.EXAMS_OFFICE);
        Semester sem = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        boolean published = sheets.findBySemesterIdOrderByProgrammeIdAscYearOfStudyAsc(semesterId).stream()
                .anyMatch(s -> s.getRound() == ResultSheet.Round.MAIN && s.getStatus() == ResultSheet.Status.PUBLISHED);
        if (!published) throw new BusinessRuleException("NO_PUBLISHED_RESULTS", "Publish the semester's results before opening resits");
        ResitSession s = sessions.findBySemesterId(semesterId).orElseGet(ResitSession::new);
        boolean created = s.getId() == null;
        if (!created && resits.findBySessionIdAndStatus(s.getId(), Resit.Status.REGISTERED).stream().anyMatch(r -> r.getInvoiceId() != null)
                && (s.getSupplementaryFee().compareTo(req.supplementaryFee()) != 0 || s.getSpecialFee().compareTo(req.specialFee()) != 0)) {
            throw new BusinessRuleException("FEES_INVOICED", "Students have already been invoiced at the current fees; the fees cannot change");
        }
        s.setSemester(sem);
        s.setSupplementaryFee(req.supplementaryFee());
        s.setSpecialFee(req.specialFee());
        s.setRegistrationCloses(req.registrationCloses());
        s.setExamPeriod(req.examPeriod() == null || req.examPeriod().isBlank() ? null : req.examPeriod().trim());
        if (created) s.setCreatedByName(CurrentUser.name());
        sessions.save(s);
        audit.record(created ? "RESIT_SESSION_OPENED" : "RESIT_SESSION_UPDATED", "ResitSession", s.getId(), Map.of("semester", sem.getName(),
                "supplementaryFee", req.supplementaryFee(), "specialFee", req.specialFee(), "closes", req.registrationCloses().toString()));
        return view(s);
    }

    @Transactional(readOnly = true)
    public SessionView get(Long semesterId) {
        return sessions.findBySemesterId(semesterId).map(this::view).orElse(null);
    }

    private SessionView view(ResitSession s) {
        Map<Long, List<ModuleResult>> eligible = eligible(s.getSemester().getId());
        List<Resit> registered = resits.findBySessionIdAndStatus(s.getId(), Resit.Status.REGISTERED);
        Map<Long, ModuleOffering> byId = offerings.findBySemesterIdOrderByIdAsc(s.getSemester().getId()).stream()
                .collect(Collectors.toMap(ModuleOffering::getId, Function.identity()));
        Map<Long, List<Resit>> perOffering = registered.stream().collect(Collectors.groupingBy(Resit::getOfferingId));
        List<SessionModule> modules = perOffering.entrySet().stream().map(e -> {
            ModuleOffering o = byId.get(e.getKey());
            List<Resit> rs = e.getValue();
            return new SessionModule(o.getId(), o.getModule().getCode(), o.getModule().getTitle(),
                    rs.stream().filter(r -> r.getKind() == Resit.Kind.SUPPLEMENTARY).count(), rs.stream().filter(r -> r.getKind() == Resit.Kind.SPECIAL).count(),
                    rs.stream().filter(r -> !paid(r)).count(), o.getResitStatus() == null ? "DRAFT" : o.getResitStatus().name());
        }).sorted(Comparator.comparing(SessionModule::code)).toList();
        LocalDate today = LocalDate.now(ZONE);
        return new SessionView(s.getId(), s.getSemester().getId(), s.getSemester().getName(), s.getSemester().getAcademicYear().getCode(),
                s.getSupplementaryFee(), s.getSpecialFee(), s.getRegistrationCloses(), s.getExamPeriod(), !today.isAfter(s.getRegistrationCloses()),
                eligible.size(), eligible.values().stream().mapToLong(List::size).sum(), registered.size(), modules);
    }

    /**
     * Students eligible for resits in a semester, with their modules: from published MAIN sheets, a supplementary decision opens the
     * failed modules, a special-examination decision the missed ones (and any failed ones, as supplementaries).
     */
    private Map<Long, List<ModuleResult>> eligible(Long semesterId) {
        Map<Long, List<ModuleResult>> out = new HashMap<>();
        for (ResultSheet sheet : sheets.findBySemesterIdOrderByProgrammeIdAscYearOfStudyAsc(semesterId)) {
            if (sheet.getRound() != ResultSheet.Round.MAIN || sheet.getStatus() != ResultSheet.Status.PUBLISHED) continue;
            for (SemesterResult r : sheet.getResults()) {
                if (r.getDecision() != Decision.SUPPLEMENTARY && r.getDecision() != Decision.SPECIAL_EXAM) continue;
                List<ModuleResult> mods = moduleResults.findByStudentId(r.getStudentId()).stream()
                        .filter(m -> offerings.findById(m.getOfferingId()).map(o -> o.getSemester().getId().equals(semesterId)).orElse(false))
                        .filter(m -> m.getRemark() == Remark.FAIL || (r.getDecision() == Decision.SPECIAL_EXAM && m.getRemark() == Remark.ABSENT))
                        .toList();
                if (!mods.isEmpty()) out.put(r.getStudentId(), mods);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ student portal

    /** The signed-in student's resits: the latest session they are eligible for, with what they registered and what they owe. */
    @Transactional(readOnly = true)
    public StudentResits mine() {
        Student st = me();
        for (ResitSession s : sessions.findAll().stream().sorted(Comparator.comparing((ResitSession x) -> x.getSemester().getStartDate()).reversed()).toList()) {
            List<ModuleResult> mods = eligible(s.getSemester().getId()).get(st.getId());
            if (mods == null) continue;
            Map<Long, Resit> mine = resits.findByStudentIdAndStatus(st.getId(), Resit.Status.REGISTERED).stream()
                    .filter(r -> r.getSession().getId().equals(s.getId())).collect(Collectors.toMap(r -> r.getModuleResult().getId(), Function.identity()));
            List<EligibleModule> list = new ArrayList<>();
            for (ModuleResult m : mods) {
                ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
                Resit r = mine.get(m.getId());
                Resit.Kind kind = m.getRemark() == Remark.ABSENT ? Resit.Kind.SPECIAL : Resit.Kind.SUPPLEMENTARY;
                list.add(new EligibleModule(m.getId(), o.getModule().getCode(), o.getModule().getTitle(), m.getCredits(), kind.name(), m.getGrade(),
                        m.getRemark().name(), kind == Resit.Kind.SPECIAL ? s.getSpecialFee() : s.getSupplementaryFee(), r == null ? null : r.getId(),
                        r == null ? null : r.getStatus().name(), r != null && paid(r), r == null || !r.isLocked() ? null : r.getGrade(),
                        r == null || !r.isLocked() ? null : String.valueOf(r.getRemark())));
            }
            list.sort(Comparator.comparing(EligibleModule::code));
            Invoice inv = mine.values().stream().map(Resit::getInvoiceId).filter(Objects::nonNull).distinct()
                    .map(id -> invoices.findById(id).orElse(null)).filter(i -> i != null && i.getStatus() != Invoice.Status.PAID).findFirst().orElse(null);
            ControlNumber cn = inv == null ? null : billing.currentControlNumber(inv.getId());
            return new StudentResits(s.getId(), s.getSemester().getName(), s.getSemester().getAcademicYear().getCode(), s.getRegistrationCloses(),
                    s.getExamPeriod(), !LocalDate.now(ZONE).isAfter(s.getRegistrationCloses()), list, inv == null ? null : inv.getId(),
                    inv == null ? null : inv.getInvoiceNo(), cn == null ? null : cn.getControlNo(),
                    inv == null ? null : inv.getTotalAmount().subtract(inv.getPaidAmount()));
        }
        return new StudentResits(null, null, null, null, null, false, List.of(), null, null, null, null);
    }

    @Transactional
    public StudentResits register(Long sessionId, List<Long> moduleResultIds) {
        Student st = me();
        ResitSession s = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Resit session", sessionId));
        if (LocalDate.now(ZONE).isAfter(s.getRegistrationCloses())) {
            throw new BusinessRuleException("RESIT_REGISTRATION_CLOSED", "Registration for supplementary and special examinations closed on " + s.getRegistrationCloses());
        }
        Map<Long, ModuleResult> mine = eligible(s.getSemester().getId()).getOrDefault(st.getId(), List.of()).stream()
                .collect(Collectors.toMap(ModuleResult::getId, Function.identity()));
        List<Resit> created = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<BillingService.NewInvoice.Line> lines = new ArrayList<>();
        for (Long id : new HashSet<>(moduleResultIds)) {
            ModuleResult m = mine.get(id);
            if (m == null) throw new BusinessRuleException("NOT_ELIGIBLE", "You are not eligible for a resit in that module");
            if (resits.findByModuleResultId(id).isPresent()) throw new BusinessRuleException("ALREADY_REGISTERED", "You are already registered for that resit");
            ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
            Resit r = new Resit();
            r.setSession(s);
            r.setModuleResult(m);
            r.setOfferingId(o.getId());
            r.setStudentId(st.getId());
            r.setKind(m.getRemark() == Remark.ABSENT ? Resit.Kind.SPECIAL : Resit.Kind.SUPPLEMENTARY);
            r.setFee(r.getKind() == Resit.Kind.SPECIAL ? s.getSpecialFee() : s.getSupplementaryFee());
            r.setCaTotal(m.getCaTotal());
            resits.save(r);
            created.add(r);
            if (r.getFee().signum() > 0) {
                total = total.add(r.getFee());
                lines.add(new BillingService.NewInvoice.Line(r.getKind() == Resit.Kind.SPECIAL ? "SPECIAL" : "SUPP",
                        (r.getKind() == Resit.Kind.SPECIAL ? "Special examination " : "Supplementary examination ") + o.getModule().getCode(), r.getFee()));
            }
            if (o.getResitStatus() == null) o.setResitStatus(ModuleOffering.ResitStatus.DRAFT);
        }
        if (total.signum() > 0) {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, st.getId(), st.fullName(), st.getPhone(),
                    st.getEmail(), PURPOSE, s.getId(), "Supplementary and special examinations, " + s.getSemester().getName() + " "
                    + s.getSemester().getAcademicYear().getCode(), lines, s.getSemester().getAcademicYear().getId(), null));
            created.forEach(r -> r.setInvoiceId(inv.getId()));
            billing.ensureControlNumber(inv.getId());
        }
        audit.record("RESITS_REGISTERED", "ResitSession", s.getId(), Map.of("student", st.getRegistrationNo(), "modules", created.size(), "fee", total));
        return mine();
    }

    // ------------------------------------------------------------------ resit marks (lecturer → Head of Department)

    @Transactional(readOnly = true)
    public ResitMarksView marks(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        if (!isLecturer(o) && !access.moderates(o.getModule()) && !access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC)) {
            throw new AccessDeniedException("Not your module");
        }
        return view(o);
    }

    @Transactional
    public ResitMarksView save(Long offeringId, List<ResitScore> entries) {
        ModuleOffering o = offering(offeringId);
        requireLecturer(o);
        Map<Long, Resit> mine = resits.findByOfferingIdAndStatus(o.getId(), Resit.Status.REGISTERED).stream()
                .collect(Collectors.toMap(Resit::getId, Function.identity()));
        Map<Long, RegulationSet> regs = new HashMap<>();
        for (ResitScore e : entries) {
            Resit r = mine.get(e.resitId());
            if (r == null) throw new BusinessRuleException("NOT_IN_CLASS", "That resit is not part of this module");
            if (!paid(r)) throw new BusinessRuleException("FEE_NOT_PAID", "The student has not paid for this resit yet");
            if (e.absent() && e.score() != null) throw new BusinessRuleException("INVALID_SCORE", "A student marked absent has no score");
            RegulationSet rs = regs.computeIfAbsent(r.getStudentId(), sid -> regulationFor(sid, o));
            r.setScore(e.absent() ? null : e.score());
            r.setAbsent(e.absent());
            Grading.ModuleOutcome out = Grading.resit(rs, r.getCaTotal(), r.getScore(), r.isAbsent(), r.getKind() == Resit.Kind.SUPPLEMENTARY);
            r.setSeTotal(out.seTotal());
            r.setTotal(out.total());
            r.setGrade(out.grade());
            r.setGradePoints(out.points());
            r.setRemark(out.remark());
            r.setFailReason(out.failReason());
            r.setCapped(Grading.capped(rs, out));
            r.setEnteredByName(CurrentUser.name());
            r.setEnteredAt(OffsetDateTime.now());
        }
        o.setResitStatus(ModuleOffering.ResitStatus.DRAFT);
        audit.record("RESIT_MARKS_ENTERED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "entries", entries.size()));
        return view(o);
    }

    @Transactional
    public ResitMarksView submit(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        requireLecturer(o);
        List<Resit> list = resits.findByOfferingIdAndStatus(o.getId(), Resit.Status.REGISTERED).stream().filter(this::paid).toList();
        if (list.isEmpty()) throw new BusinessRuleException("NO_RESITS", "No paid resits to submit");
        long missing = list.stream().filter(r -> r.getRemark() == null || r.getRemark() == Remark.INCOMPLETE).count();
        if (missing > 0) throw new BusinessRuleException("MARKS_INCOMPLETE", missing + " resit mark" + (missing == 1 ? " is" : "s are") + " missing");
        o.setResitStatus(ModuleOffering.ResitStatus.SUBMITTED);
        o.setResitSubmittedByName(CurrentUser.name());
        o.setResitSubmittedAt(OffsetDateTime.now());
        o.setResitReturnedNote(null);
        audit.record("RESIT_MARKS_SUBMITTED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "students", list.size()));
        return view(o);
    }

    @Transactional
    public ResitMarksView approve(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        if (o.getResitStatus() != ModuleOffering.ResitStatus.SUBMITTED) throw new BusinessRuleException("WRONG_STAGE", "Only submitted resit marks can be approved");
        if (!access.moderates(o.getModule())) throw new AccessDeniedException("Only the module's Head of Department approves resit marks");
        o.setResitStatus(ModuleOffering.ResitStatus.APPROVED);
        o.setResitApprovedByName(CurrentUser.name());
        o.setResitApprovedAt(OffsetDateTime.now());
        audit.record("RESIT_MARKS_APPROVED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode()));
        return view(o);
    }

    @Transactional
    public ResitMarksView returnMarks(Long offeringId, String note) {
        ModuleOffering o = offering(offeringId);
        boolean locked = resits.findByOfferingIdAndStatus(o.getId(), Resit.Status.REGISTERED).stream().anyMatch(Resit::isLocked);
        if (locked) throw new BusinessRuleException("RESULTS_LOCKED", "These resit results are confirmed by the Governing Board");
        boolean canReturn = (o.getResitStatus() == ModuleOffering.ResitStatus.SUBMITTED && access.moderates(o.getModule()))
                || (o.getResitStatus() == ModuleOffering.ResitStatus.APPROVED && access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER));
        if (!canReturn) throw new AccessDeniedException("Not allowed to return these resit marks");
        o.setResitStatus(ModuleOffering.ResitStatus.DRAFT);
        o.setResitReturnedNote(note.trim());
        audit.record("RESIT_MARKS_RETURNED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "note", note.trim()));
        return view(o);
    }

    private ResitMarksView view(ModuleOffering o) {
        List<Resit> list = resits.findByOfferingIdAndStatus(o.getId(), Resit.Status.REGISTERED);
        Map<Long, Student> people = students.findAllById(list.stream().map(Resit::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<ResitRow> rows = list.stream().map(r -> {
            Student st = people.get(r.getStudentId());
            return new ResitRow(r.getId(), st.getId(), st.getRegistrationNo(), st.fullName(), st.getProgramme().getCode(), r.getKind().name(), paid(r),
                    r.getCaTotal(), r.getModuleResult().getGrade() == null ? r.getModuleResult().getRemark().name() : r.getModuleResult().getGrade(),
                    r.getScore(), r.isAbsent(), r.getSeTotal(), r.getTotal(), r.getGrade(), r.isCapped(),
                    r.getRemark() == null ? null : r.getRemark().name(), r.getFailReason());
        }).sorted(Comparator.comparing(ResitRow::registrationNo)).toList();
        boolean draft = o.getResitStatus() == null || o.getResitStatus() == ModuleOffering.ResitStatus.DRAFT;
        boolean lecturer = isLecturer(o) && draft && list.stream().noneMatch(Resit::isLocked);
        RegulationSet rs = o.getRegulationSet();
        return new ResitMarksView(o.getId(), o.getModule().getCode(), o.getModule().getTitle(), o.getResitStatus() == null ? "DRAFT" : o.getResitStatus().name(),
                o.getResitSubmittedByName(), o.getResitSubmittedAt(), o.getResitApprovedByName(), o.getResitApprovedAt(), o.getResitReturnedNote(),
                rs.getSeWeight(), rs.getSuppMaxGrade(), rows, lecturer, lecturer && rows.stream().anyMatch(ResitRow::paid),
                o.getResitStatus() == ModuleOffering.ResitStatus.SUBMITTED && access.moderates(o.getModule()),
                list.stream().noneMatch(Resit::isLocked) && ((o.getResitStatus() == ModuleOffering.ResitStatus.SUBMITTED && access.moderates(o.getModule()))
                        || (o.getResitStatus() == ModuleOffering.ResitStatus.APPROVED && access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER))));
    }

    // ------------------------------------------------------------------ helpers

    /** A resit counts once its fee is paid, or straight away when there is no fee. */
    private boolean paid(Resit r) {
        if (r.getFee().signum() == 0 || r.getInvoiceId() == null) return true;
        return invoices.findById(r.getInvoiceId()).map(i -> i.getStatus() == Invoice.Status.PAID).orElse(false);
    }

    private RegulationSet regulationFor(Long studentId, ModuleOffering o) {
        Programme p = students.findById(studentId).orElseThrow().getProgramme();
        return p.getAwardFamily() == Programme.AwardFamily.NTA
                ? regulations.forLevel(p.getAwardFamily(), p.getAwardLevel(), o.getSemester().getAcademicYear()) : o.getRegulationSet();
    }

    private ModuleOffering offering(Long id) {
        return offerings.findById(id).orElseThrow(() -> new NotFoundException("Module offering", id));
    }

    private boolean isLecturer(ModuleOffering o) {
        Long me = access.me();
        return o.getStaff().stream().anyMatch(s -> s.getUserId().equals(me));
    }

    private void requireLecturer(ModuleOffering o) {
        if (!isLecturer(o)) throw new AccessDeniedException("Only the module's lecturers enter its resit marks");
        if (o.getResitStatus() == ModuleOffering.ResitStatus.SUBMITTED || o.getResitStatus() == ModuleOffering.ResitStatus.APPROVED) {
            throw new BusinessRuleException("RESULTS_SUBMITTED", "These resit marks have been submitted. They can change only if returned to you.");
        }
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }
}
