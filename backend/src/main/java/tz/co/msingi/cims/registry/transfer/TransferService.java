package tz.co.msingi.cims.registry.transfer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.campus.CampusService;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.DataScope;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.idcard.IdCardService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Programme and campus transfers (FR-REG-05, BR-13). Registry (or the student, from the portal) asks with a reason; for a programme
 * change the Head of the receiving department recommends it; the Deputy Principal (Academic) approves or rejects it, never the person
 * who asked. Approval moves the student at once: programme, campus and year of study change, the registration number does not, the
 * curriculum is chosen again at the next semester registration, and ID cards showing the old programme are withdrawn so a new one is
 * printed. Every transfer stays in the student's history.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final List<Student.Status> MOVABLE = List.of(Student.Status.ADMITTED, Student.Status.ACTIVE, Student.Status.POSTPONED);

    private final StudentTransferRepository transfers;
    private final StudentRepository students;
    private final ProgrammeRepository programmes;
    private final CampusService campuses;
    private final DataScope scope;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;
    private final IdCardService idCards;
    private final JdbcTemplate jdbc;

    public record TransferRequest(Long toProgrammeId, Long toCampusId, @Min(1) @Max(8) Short toYearOfStudy,
                                  @NotBlank @Size(max = 1000) String reason, LocalDate effectiveDate) {
    }

    public record DecisionRequest(boolean approve, @Size(max = 1000) String note) {
    }

    public record NoteRequest(@Size(max = 1000) String note) {
    }

    public record Option(Long id, String code, String name, int years, List<Long> campusIds) {
    }

    public record CampusOption(Long id, String name, boolean main) {
    }

    public record Options(Long programmeId, String programme, Long campusId, String campus, int yearOfStudy, String status,
                          boolean openTransfer, List<Option> programmes, List<CampusOption> campuses) {
    }

    public record TransferView(Long id, Long studentId, String registrationNo, String studentName, String kind,
                               String fromProgramme, String toProgramme, String fromCampus, String toCampus, int fromYear, int toYear,
                               String reason, LocalDate effectiveDate, String state, boolean requestedByStudent, String requestedByName,
                               OffsetDateTime requestedAt, String recommendedByName, OffsetDateTime recommendedAt, String recommendationNote,
                               String decidedByName, OffsetDateTime decidedAt, String decisionNote,
                               boolean canRecommend, boolean canDecide, boolean canCancel) {
    }

    // ------------------------------------------------------------------ staff

    @Transactional(readOnly = true)
    public Options options(Long studentId) {
        return options(student(studentId));
    }

    @Transactional
    public TransferView request(Long studentId, TransferRequest req) {
        return create(student(studentId), req, false);
    }

    @Transactional
    public TransferView recommend(Long id, String note) {
        StudentTransfer t = transfer(id);
        if (!canRecommend(t)) {
            if (t.getState() != StudentTransfer.State.REQUESTED) throw stateError(t);
            throw new AccessDeniedException("Only the Head of the receiving department can recommend this transfer");
        }
        t.setState(StudentTransfer.State.RECOMMENDED);
        t.setRecommendedByName(CurrentUser.name());
        t.setRecommendedAt(OffsetDateTime.now());
        t.setRecommendationNote(blank(note));
        audit.record("TRANSFER_RECOMMENDED", "Student", t.getStudent().getId(), details(t));
        return view(t);
    }

    @Transactional
    public TransferView decide(Long id, boolean approve, String note) {
        StudentTransfer t = transfer(id);
        if (!StudentTransfer.OPEN.contains(t.getState())) throw stateError(t);
        if (!access.has(Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC)) {
            throw new AccessDeniedException("Only the Deputy Principal (Academic) decides transfers");
        }
        if (!t.isRequestedByStudent() && Objects.equals(t.getRequestedById(), CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("OWN_REQUEST", "You requested this transfer, so someone else must decide it");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new BusinessRuleException("REASON_REQUIRED", "Give a reason for rejecting the transfer");
        }
        t.setDecidedById(CurrentUser.id().orElse(null));
        t.setDecidedByName(CurrentUser.name());
        t.setDecidedAt(OffsetDateTime.now());
        t.setDecisionNote(blank(note));
        if (!approve) {
            t.setState(StudentTransfer.State.REJECTED);
            audit.record("TRANSFER_REJECTED", "Student", t.getStudent().getId(), details(t));
            notify(t, "was not approved");
            return view(t);
        }
        Student s = t.getStudent();
        if (!s.getProgramme().getId().equals(t.getFromProgramme().getId()) || !MOVABLE.contains(s.getStatus())) {
            throw new BusinessRuleException("STUDENT_CHANGED", "The student's programme or status has changed since the request; cancel it and ask again");
        }
        t.setState(StudentTransfer.State.APPROVED);
        boolean programmeChanged = !t.getToProgramme().getId().equals(t.getFromProgramme().getId());
        s.setProgramme(t.getToProgramme());
        s.setCampusId(t.getToCampusId());
        s.setYearOfStudy(t.getToYearOfStudy());
        if (programmeChanged) {
            s.setCurriculumId(null);   // the curriculum of the new programme is chosen at the next semester registration
            idCards.revokeAll(s, "Transferred to " + t.getToProgramme().getCode());
        }
        audit.record("STUDENT_TRANSFERRED", "Student", s.getId(), details(t));
        notify(t, "has been approved: " + describe(t));
        return view(t);
    }

    @Transactional
    public TransferView cancel(Long id, String note) {
        StudentTransfer t = transfer(id);
        if (!canCancel(t)) {
            if (!StudentTransfer.OPEN.contains(t.getState())) throw stateError(t);
            throw new AccessDeniedException("Only Registry can cancel this transfer");
        }
        t.setState(StudentTransfer.State.CANCELLED);
        t.setDecisionNote(blank(note));
        t.setDecidedByName(CurrentUser.name());
        t.setDecidedAt(OffsetDateTime.now());
        audit.record("TRANSFER_CANCELLED", "Student", t.getStudent().getId(), details(t));
        return view(t);
    }

    @Transactional(readOnly = true)
    public List<TransferView> forStudent(Long studentId) {
        student(studentId);
        return transfers.findByStudentIdOrderByRequestedAtDesc(studentId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<TransferView> list(boolean openOnly) {
        DataScope.Scope sc = scope.current();
        List<StudentTransfer> rows = openOnly ? transfers.findByStateInOrderByRequestedAtAsc(StudentTransfer.OPEN) : transfers.findTop200ByOrderByRequestedAtDesc();
        return rows.stream().filter(t -> sc.programme(t.getFromProgramme()) || sc.programme(t.getToProgramme())).map(this::view).toList();
    }

    /** Open transfers waiting for the signed-in person (Approvals inbox). */
    @Transactional(readOnly = true)
    public List<TransferView> waitingForMe() {
        return transfers.findByStateInOrderByRequestedAtAsc(StudentTransfer.OPEN).stream()
                .filter(t -> canRecommend(t) || mayDecide(t)).map(this::view).toList();
    }

    // ------------------------------------------------------------------ student portal

    @Transactional(readOnly = true)
    public Options myOptions() {
        return options(me());
    }

    @Transactional(readOnly = true)
    public List<TransferView> mine() {
        return transfers.findByStudentIdOrderByRequestedAtDesc(me().getId()).stream().map(this::view).toList();
    }

    @Transactional
    public TransferView requestMine(TransferRequest req) {
        return create(me(), req, true);
    }

    @Transactional
    public TransferView cancelMine(Long id) {
        StudentTransfer t = transfers.findById(id).orElseThrow(() -> new NotFoundException("Transfer", id));
        if (!t.getStudent().getId().equals(me().getId()) || !t.isRequestedByStudent()) throw new NotFoundException("Transfer", id);
        if (t.getState() != StudentTransfer.State.REQUESTED) throw stateError(t);
        t.setState(StudentTransfer.State.CANCELLED);
        t.setDecidedByName(t.getStudent().fullName());
        t.setDecidedAt(OffsetDateTime.now());
        audit.record("TRANSFER_CANCELLED", "Student", t.getStudent().getId(), details(t));
        return view(t);
    }

    // ------------------------------------------------------------------ helpers

    private TransferView create(Student s, TransferRequest req, boolean byStudent) {
        if (!MOVABLE.contains(s.getStatus())) {
            throw new BusinessRuleException("NOT_ALLOWED_FROM_STATUS", "Only admitted, active or postponed students can transfer");
        }
        if (transfers.existsByStudentIdAndStateIn(s.getId(), StudentTransfer.OPEN)) {
            throw new BusinessRuleException("OPEN_REQUEST", "There is already a transfer waiting for a decision; finish or cancel it first");
        }
        Programme to = req.toProgrammeId() == null ? s.getProgramme()
                : programmes.findById(req.toProgrammeId()).orElseThrow(() -> new NotFoundException("Programme", req.toProgrammeId()));
        if (!to.isActive()) throw new BusinessRuleException("INACTIVE_PROGRAMME", to.getName() + " is not offered");
        List<Long> taught = campuses.campusesOf(to.getId());
        Long fromCampus = s.getCampusId() != null ? s.getCampusId() : campuses.defaultCampusFor(s.getProgramme().getId());
        Long toCampus = req.toCampusId() != null ? req.toCampusId()
                : taught.contains(fromCampus) ? fromCampus : campuses.defaultCampusFor(to.getId());
        if (toCampus != null && !taught.isEmpty() && !taught.contains(toCampus)) {
            throw new BusinessRuleException("NOT_TAUGHT_AT_CAMPUS", to.getName() + " is not taught at " + campuses.campusName(toCampus));
        }
        boolean programmeChanges = !to.getId().equals(s.getProgramme().getId());
        boolean campusChanges = !Objects.equals(toCampus, fromCampus);
        if (!programmeChanges && !campusChanges) {
            throw new BusinessRuleException("NOTHING_CHANGES", "Choose another programme or another campus");
        }
        int years = Math.max(1, (to.getDurationSemesters() + 1) / 2);
        short toYear = req.toYearOfStudy() != null ? req.toYearOfStudy() : programmeChanges ? (short) 1 : s.getYearOfStudy();
        if (byStudent && req.toYearOfStudy() != null && req.toYearOfStudy() != (programmeChanges ? 1 : s.getYearOfStudy())) {
            throw new BusinessRuleException("YEAR_SET_BY_REGISTRY", "Registry decides the year of study you join; leave it out");
        }
        if (toYear > years) throw new BusinessRuleException("INVALID_YEAR_OF_STUDY", to.getName() + " runs for " + years + " year(s)");
        Integer pending = jdbc.queryForObject("select count(*) from semester_registrations where student_id = ? and status = 'SUBMITTED'", Integer.class, s.getId());
        if (pending != null && pending > 0) {
            throw new BusinessRuleException("REGISTRATION_PENDING", "A semester registration is waiting for approval; decide or cancel it first");
        }
        LocalDate today = LocalDate.now(ZONE);
        LocalDate effective = req.effectiveDate() == null ? today : req.effectiveDate();
        if (effective.isBefore(today.minusDays(90))) {
            throw new BusinessRuleException("EFFECTIVE_DATE_TOO_EARLY", "The effective date can be at most 90 days ago");
        }
        StudentTransfer t = new StudentTransfer();
        t.setStudent(s);
        t.setKind(programmeChanges && campusChanges ? StudentTransfer.Kind.BOTH : programmeChanges ? StudentTransfer.Kind.PROGRAMME : StudentTransfer.Kind.CAMPUS);
        t.setFromProgramme(s.getProgramme());
        t.setToProgramme(to);
        t.setFromCampusId(fromCampus);
        t.setToCampusId(toCampus);
        t.setFromYearOfStudy(s.getYearOfStudy());
        t.setToYearOfStudy(toYear);
        t.setReason(req.reason().trim());
        t.setEffectiveDate(effective);
        t.setRequestedByStudent(byStudent);
        t.setRequestedById(CurrentUser.id().orElse(null));
        t.setRequestedByName(byStudent ? s.fullName() + " (student)" : CurrentUser.name());
        t.setRequestedAt(OffsetDateTime.now());
        transfers.save(t);
        audit.record("TRANSFER_REQUESTED", "Student", s.getId(), details(t));
        return view(t);
    }

    private Options options(Student s) {
        Map<Long, List<Long>> map = campuses.programmeCampusMap();
        Long main = campuses.mainCampus() == null ? null : campuses.mainCampus().getId();
        List<Option> progs = programmes.findAll().stream().filter(Programme::isActive)
                .sorted(java.util.Comparator.comparing(Programme::getName))
                .map(p -> new Option(p.getId(), p.getCode(), p.getName(), Math.max(1, (p.getDurationSemesters() + 1) / 2),
                        map.getOrDefault(p.getId(), main == null ? List.of() : List.of(main)))).toList();
        List<CampusOption> cs = campuses.campuses().stream().filter(CampusService.CampusView::active)
                .map(c -> new CampusOption(c.id(), c.name(), c.main())).toList();
        Long campus = s.getCampusId() != null ? s.getCampusId() : campuses.defaultCampusFor(s.getProgramme().getId());
        return new Options(s.getProgramme().getId(), s.getProgramme().getName(), campus, campuses.campusName(campus), s.getYearOfStudy(),
                s.getStatus().name(), transfers.existsByStudentIdAndStateIn(s.getId(), StudentTransfer.OPEN), progs, cs);
    }

    private void notify(StudentTransfer t, String outcome) {
        Student s = t.getStudent();
        if (s.getPhone() == null || s.getPhone().isBlank()) return;
        messaging.event("STUDENT_TRANSFERRED", s.getPhone(), Map.of("outcome", outcome, "registrationNo", s.getRegistrationNo()));
    }

    private String describe(StudentTransfer t) {
        StringBuilder b = new StringBuilder();
        if (!t.getToProgramme().getId().equals(t.getFromProgramme().getId())) b.append(t.getToProgramme().getCode()).append(" year ").append(t.getToYearOfStudy());
        if (!Objects.equals(t.getToCampusId(), t.getFromCampusId())) {
            if (b.length() > 0) b.append(", ");
            b.append(campuses.campusName(t.getToCampusId()));
        }
        return b.toString();
    }

    private boolean canRecommend(StudentTransfer t) {
        if (t.getState() != StudentTransfer.State.REQUESTED || t.getKind() == StudentTransfer.Kind.CAMPUS) return false;
        Department d = t.getToProgramme().getDepartment();
        return d != null && access.headsDepartment(d.getId());
    }

    private boolean mayDecide(StudentTransfer t) {
        return StudentTransfer.OPEN.contains(t.getState()) && access.has(Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC)
                && (t.isRequestedByStudent() || !Objects.equals(t.getRequestedById(), CurrentUser.id().orElse(null)));
    }

    private boolean canCancel(StudentTransfer t) {
        return StudentTransfer.OPEN.contains(t.getState()) && access.has(Role.INSTITUTION_ADMIN, Role.REGISTRAR);
    }

    TransferView view(StudentTransfer t) {
        Student s = t.getStudent();
        boolean staff = CurrentUser.id().isPresent() && !Objects.equals(s.getUserId(), CurrentUser.id().orElse(null));
        return new TransferView(t.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), t.getKind().name(),
                t.getFromProgramme().getCode() + " — " + t.getFromProgramme().getName(), t.getToProgramme().getCode() + " — " + t.getToProgramme().getName(),
                campuses.campusName(t.getFromCampusId()), campuses.campusName(t.getToCampusId()), t.getFromYearOfStudy(), t.getToYearOfStudy(),
                t.getReason(), t.getEffectiveDate(), t.getState().name(), t.isRequestedByStudent(), t.getRequestedByName(), t.getRequestedAt(),
                t.getRecommendedByName(), t.getRecommendedAt(), t.getRecommendationNote(), t.getDecidedByName(), t.getDecidedAt(),
                t.getDecisionNote(), staff && canRecommend(t), staff && mayDecide(t), staff && canCancel(t));
    }

    private Map<String, Object> details(StudentTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transferId", t.getId());
        m.put("registrationNo", t.getStudent().getRegistrationNo());
        m.put("kind", t.getKind().name());
        m.put("fromProgramme", t.getFromProgramme().getCode());
        m.put("toProgramme", t.getToProgramme().getCode());
        if (t.getFromCampusId() != null) m.put("fromCampus", t.getFromCampusId());
        if (t.getToCampusId() != null) m.put("toCampus", t.getToCampusId());
        m.put("fromYear", t.getFromYearOfStudy());
        m.put("toYear", t.getToYearOfStudy());
        m.put("state", t.getState().name());
        return m;
    }

    private Student student(Long id) {
        Student s = students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
        scope.requireProgramme(s.getProgramme().getId());
        return s;
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private StudentTransfer transfer(Long id) {
        StudentTransfer t = transfers.findById(id).orElseThrow(() -> new NotFoundException("Transfer", id));
        DataScope.Scope sc = scope.current();
        if (!sc.programme(t.getFromProgramme()) && !sc.programme(t.getToProgramme())) throw new NotFoundException("Transfer", id);
        return t;
    }

    private static BusinessRuleException stateError(StudentTransfer t) {
        return new BusinessRuleException("INVALID_STATE", "This transfer is already " + t.getState().name().toLowerCase(Locale.ROOT));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
