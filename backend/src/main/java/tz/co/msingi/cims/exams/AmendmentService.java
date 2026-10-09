package tz.co.msingi.cims.exams;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.AmendmentRequest;
import tz.co.msingi.cims.exams.ExamDtos.AmendmentView;
import tz.co.msingi.cims.exams.ExamDtos.StudentModuleRecord;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleResultRepository;
import tz.co.msingi.cims.exams.ExamRepositories.ResultAmendmentRepository;
import tz.co.msingi.cims.exams.ExamRepositories.SemesterResultRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Amendments to confirmed results (BP-08 exception: "post-approval error: amendment request with justification, re-approval and
 * full audit"). The Examinations Officer proposes corrected CA and SE totals with a reason; the administrator (someone else) approves;
 * only then is the database lock opened, for this transaction, to change the result and recompute the student's sheets.
 */
@Service
@RequiredArgsConstructor
public class AmendmentService {

    private final ResultAmendmentRepository amendments;
    private final tz.co.msingi.cims.exams.ExamRepositories.ResitRepository resits;
    private final ModuleResultRepository moduleResults;
    private final ModuleOfferingRepository offerings;
    private final SemesterResultRepository semesterResults;
    private final StudentRepository students;
    private final RegulationService regulations;
    private final ResultSheetService sheets;
    private final Messaging messaging;
    private final ExamAccess access;
    private final AuditService audit;
    private final EntityManager em;

    /** A student's module results, to choose the one to amend. */
    @Transactional(readOnly = true)
    public List<StudentModuleRecord> records(Long studentId) {
        access.require("view results for amendment", ExamAccess.EXAMS_OFFICE);
        List<StudentModuleRecord> out = new ArrayList<>();
        for (ModuleResult m : moduleResults.findByStudentId(studentId)) {
            ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
            Resit r = resits.findByModuleResultId(m.getId()).filter(x -> x.getStatus() == Resit.Status.REGISTERED).orElse(null);
            out.add(new StudentModuleRecord(m.getId(), o.getSemester().getName() + " " + o.getSemester().getAcademicYear().getCode(), o.getModule().getCode(),
                    o.getModule().getTitle(), m.getCaTotal(), m.getSeTotal(), m.getTotal(), m.getGrade(), m.getRemark().name(), m.isLocked(),
                    amendments.existsByModuleResultIdAndStatus(m.getId(), ResultAmendment.Status.PENDING), r == null ? null : r.getKind().name(),
                    r == null ? null : r.getGrade(), r == null || r.getRemark() == null ? null : r.getRemark().name()));
        }
        out.sort(Comparator.comparing(StudentModuleRecord::semester).thenComparing(StudentModuleRecord::code));
        return out;
    }

    @Transactional(readOnly = true)
    public List<AmendmentView> list() {
        access.require("view amendments", Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC);
        Map<Long, ResultAmendment> all = new LinkedHashMap<>();
        amendments.findByStatusOrderByRequestedAtDesc(ResultAmendment.Status.PENDING).forEach(a -> all.put(a.getId(), a));
        amendments.findTop100ByOrderByRequestedAtDesc().forEach(a -> all.putIfAbsent(a.getId(), a));
        return all.values().stream().map(this::view).toList();
    }

    @Transactional
    public AmendmentView request(AmendmentRequest req) {
        access.require("request an amendment", ExamAccess.EXAMS_OFFICE);
        ModuleResult m = moduleResults.findById(req.moduleResultId()).orElseThrow(() -> new NotFoundException("Module result", req.moduleResultId()));
        if (!m.isLocked()) {
            throw new BusinessRuleException("NOT_CONFIRMED", "This result is not confirmed yet: correct it through marks entry (return it to the lecturer)");
        }
        if (amendments.existsByModuleResultIdAndStatus(m.getId(), ResultAmendment.Status.PENDING)) {
            throw new BusinessRuleException("AMENDMENT_PENDING", "An amendment of this result is already waiting for approval");
        }
        RegulationSet rs = regulationFor(m);
        if (req.caTotal().compareTo(rs.getCaWeight()) > 0 || req.seTotal().compareTo(rs.getSeWeight()) > 0) {
            throw new BusinessRuleException("INVALID_SCORE", "CA is out of " + Grading.plain(rs.getCaWeight()) + " and SE out of " + Grading.plain(rs.getSeWeight()));
        }
        Grading.ModuleOutcome out = Grading.fromTotals(rs, req.caTotal(), req.seTotal());
        ResultAmendment a = new ResultAmendment();
        a.setModuleResult(m);
        a.setStudentId(m.getStudentId());
        a.setOldCaTotal(m.getCaTotal());
        a.setOldSeTotal(m.getSeTotal());
        a.setOldTotal(m.getTotal());
        a.setOldGrade(m.getGrade());
        a.setOldRemark(m.getRemark().name());
        a.setNewCaTotal(req.caTotal());
        a.setNewSeTotal(req.seTotal());
        a.setNewTotal(out.total());
        a.setNewGrade(out.grade());
        a.setNewRemark(out.remark().name());
        a.setReason(req.reason().trim());
        a.setEvidenceRef(req.evidenceRef() == null || req.evidenceRef().isBlank() ? null : req.evidenceRef().trim());
        a.setRequestedBy(access.me());
        a.setRequestedByName(CurrentUser.name());
        amendments.save(a);
        audit.record("RESULT_AMENDMENT_REQUESTED", "ModuleResult", m.getId(), Map.of("from", String.valueOf(m.getGrade()), "to", out.grade(),
                "reason", a.getReason()));
        return view(a);
    }

    @Transactional
    public AmendmentView approve(Long id, String note) {
        access.require("approve an amendment", Role.INSTITUTION_ADMIN);
        ResultAmendment a = pending(id);
        if (Objects.equals(a.getRequestedBy(), access.me())) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "The person who requested an amendment cannot approve it");
        }
        ModuleResult m = a.getModuleResult();
        RegulationSet rs = regulationFor(m);
        Grading.ModuleOutcome out = Grading.fromTotals(rs, a.getNewCaTotal(), a.getNewSeTotal());
        // open the database lock (BRU-13) for this transaction only
        em.createNativeQuery("select set_config('cims.results_amendment', 'on', true)").getSingleResult();
        m.setCaTotal(out.caTotal());
        m.setSeTotal(out.seTotal());
        m.setTotal(out.total());
        m.setGrade(out.grade());
        m.setGradePoints(out.points());
        m.setRemark(out.remark());
        m.setFailReason(out.failReason());
        m.setComputedAt(OffsetDateTime.now());
        em.flush();
        ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
        // the student's sheets of that semester, then every later confirmed semester (their CGPA includes this one)
        List<ResultSheet> affected = semesterResults.findByStudentIdOrderByIdAsc(m.getStudentId()).stream().map(SemesterResult::getSheet)
                .filter(s -> !s.getSemester().getStartDate().isBefore(o.getSemester().getStartDate()))
                .sorted(Comparator.comparing((ResultSheet s) -> s.getSemester().getStartDate()).thenComparing(ResultSheet::getRound))
                .collect(Collectors.toCollection(ArrayList::new));
        for (ResultSheet s : affected) sheets.recomputeStudent(s, m.getStudentId());
        em.flush();
        a.setStatus(ResultAmendment.Status.APPROVED);
        a.setDecidedByName(CurrentUser.name());
        a.setDecidedAt(OffsetDateTime.now());
        a.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        audit.record("RESULT_AMENDED", "ModuleResult", m.getId(), Map.of("module", o.getModule().getCode(), "from", String.valueOf(a.getOldGrade()),
                "to", out.grade(), "fromTotal", String.valueOf(a.getOldTotal()), "toTotal", String.valueOf(out.total()), "reason", a.getReason(),
                "requestedBy", a.getRequestedByName(), "sheets", affected.size()));
        Student st = students.findById(m.getStudentId()).orElseThrow();
        boolean published = affected.stream().anyMatch(s -> s.getStatus() == ResultSheet.Status.PUBLISHED);
        if (published) {
            messaging.event("RESULT_AMENDED", st.getPhone(), Map.of("module", o.getModule().getCode(),
                    "semester", o.getSemester().getName() + " " + o.getSemester().getAcademicYear().getCode(), "grade", out.grade()));
        }
        return view(a);
    }

    @Transactional
    public AmendmentView reject(Long id, String note) {
        access.require("reject an amendment", Role.INSTITUTION_ADMIN);
        ResultAmendment a = pending(id);
        a.setStatus(ResultAmendment.Status.REJECTED);
        a.setDecidedByName(CurrentUser.name());
        a.setDecidedAt(OffsetDateTime.now());
        a.setDecisionNote(note.trim());
        audit.record("RESULT_AMENDMENT_REJECTED", "ModuleResult", a.getModuleResult().getId(), Map.of("note", note.trim()));
        return view(a);
    }

    private ResultAmendment pending(Long id) {
        ResultAmendment a = amendments.findById(id).orElseThrow(() -> new NotFoundException("Amendment", id));
        if (a.getStatus() != ResultAmendment.Status.PENDING) throw new BusinessRuleException("ALREADY_DECIDED", "This amendment has already been decided");
        return a;
    }

    private RegulationSet regulationFor(ModuleResult m) {
        Programme p = students.findById(m.getStudentId()).orElseThrow().getProgramme();
        ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
        return p.getAwardFamily() == Programme.AwardFamily.NTA
                ? regulations.forLevel(p.getAwardFamily(), p.getAwardLevel(), o.getSemester().getAcademicYear()) : o.getRegulationSet();
    }

    private AmendmentView view(ResultAmendment a) {
        ModuleResult m = a.getModuleResult();
        ModuleOffering o = offerings.findById(m.getOfferingId()).orElseThrow();
        Student st = students.findById(a.getStudentId()).orElseThrow();
        boolean canDecide = a.getStatus() == ResultAmendment.Status.PENDING && access.has(Role.INSTITUTION_ADMIN)
                && !Objects.equals(a.getRequestedBy(), access.me());
        return new AmendmentView(a.getId(), m.getId(), st.getId(), st.getRegistrationNo(), st.fullName(), o.getModule().getCode(), o.getModule().getTitle(),
                o.getSemester().getName() + " " + o.getSemester().getAcademicYear().getCode(), a.getOldCaTotal(), a.getOldSeTotal(), a.getOldTotal(),
                a.getOldGrade(), a.getOldRemark(), a.getNewCaTotal(), a.getNewSeTotal(), a.getNewTotal(), a.getNewGrade(), a.getNewRemark(),
                a.getReason(), a.getEvidenceRef(), a.getStatus().name(), a.getRequestedByName(), a.getRequestedAt(), a.getDecidedByName(),
                a.getDecidedAt(), a.getDecisionNote(), canDecide);
    }

}
