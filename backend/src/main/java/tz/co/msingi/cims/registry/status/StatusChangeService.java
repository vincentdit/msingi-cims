package tz.co.msingi.cims.registry.status;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.DataScope;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.idcard.IdCardService;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusChangeRequest;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusChangeView;
import tz.co.msingi.cims.registry.status.StatusChangeDtos.StatusOptions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Student status changes (FR-REG-04, BP-14). Registry requests a change with a reason and effective date; the Head of the
 * student's department recommends it; the Deputy Principal (Academic) approves or rejects it (never the person who requested
 * it); an approved change is applied on its effective date — at once if that date has come, otherwise by the morning job.
 * Applying sets the student's status, tells the student by SMS and, when their studies end, revokes their ID card; the bursary and
 * the hostel office follow up through {@link StudentStatusAppliedEvent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusChangeService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_BACKDATE_DAYS = 90;

    private final StatusChangeRepository changes;
    private final StudentRepository students;
    private final TenantRepository tenants;
    private final DataScope scope;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;
    private final IdCardService idCards;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public StatusOptions options(Long studentId) {
        Student s = student(studentId);
        List<StatusChange.Type> allowed = Arrays.stream(StatusChange.Type.values()).filter(t -> t.from.contains(s.getStatus())).toList();
        return new StatusOptions(s.getStatus().name(), allowed, changes.existsByStudentIdAndStateIn(studentId, StatusChange.OPEN));
    }

    @Transactional
    public StatusChangeView request(Long studentId, StatusChangeRequest req) {
        Student s = student(studentId);
        StatusChange.Type type = req.type();
        if (!type.from.contains(s.getStatus())) {
            throw new BusinessRuleException("NOT_ALLOWED_FROM_STATUS",
                    "A " + label(type) + " cannot be requested for a student who is " + s.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (changes.existsByStudentIdAndStateIn(studentId, StatusChange.OPEN)) {
            throw new BusinessRuleException("OPEN_REQUEST", "This student already has a status change waiting; finish or cancel it first");
        }
        LocalDate today = today();
        if (req.effectiveDate().isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw new BusinessRuleException("EFFECTIVE_DATE_TOO_EARLY", "The effective date can be at most " + MAX_BACKDATE_DAYS + " days ago");
        }
        if (type == StatusChange.Type.POSTPONEMENT && req.untilDate() == null) {
            throw new BusinessRuleException("UNTIL_DATE_REQUIRED", "Give the date the postponement ends (when the student is expected back)");
        }
        boolean takesUntil = type == StatusChange.Type.POSTPONEMENT || type == StatusChange.Type.SUSPENSION;
        if (req.untilDate() != null && !takesUntil) {
            throw new BusinessRuleException("UNTIL_DATE_NOT_USED", "An end date applies only to postponement and suspension");
        }
        if (req.untilDate() != null && !req.untilDate().isAfter(req.effectiveDate())) {
            throw new BusinessRuleException("UNTIL_BEFORE_EFFECTIVE", "The end date must be after the effective date");
        }
        StatusChange c = new StatusChange();
        c.setStudent(s);
        c.setType(type);
        c.setFromStatus(s.getStatus());
        c.setToStatus(type.target);
        c.setReason(req.reason().trim());
        c.setReference(blankToNull(req.reference()));
        c.setEffectiveDate(req.effectiveDate());
        c.setUntilDate(req.untilDate());
        c.setRequestedById(CurrentUser.id().orElse(null));
        c.setRequestedByName(CurrentUser.name());
        c.setRequestedAt(OffsetDateTime.now());
        changes.save(c);
        audit.record("STATUS_CHANGE_REQUESTED", "Student", s.getId(), details(c));
        return view(c);
    }

    @Transactional
    public StatusChangeView recommend(Long id, String note) {
        StatusChange c = change(id);
        if (!canRecommend(c)) {
            if (c.getState() != StatusChange.State.REQUESTED) throw stateError(c);
            throw new AccessDeniedException("Only the Head of the student's department can recommend this change");
        }
        c.setState(StatusChange.State.RECOMMENDED);
        c.setRecommendedByName(CurrentUser.name());
        c.setRecommendedAt(OffsetDateTime.now());
        c.setRecommendationNote(blankToNull(note));
        audit.record("STATUS_CHANGE_RECOMMENDED", "Student", c.getStudent().getId(), details(c));
        return view(c);
    }

    @Transactional
    public StatusChangeView decide(Long id, boolean approve, String note) {
        StatusChange c = change(id);
        if (c.getState() != StatusChange.State.REQUESTED && c.getState() != StatusChange.State.RECOMMENDED) throw stateError(c);
        if (!access.has(Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC)) {
            throw new AccessDeniedException("Only the Deputy Principal (Academic) decides status changes");
        }
        if (Objects.equals(c.getRequestedById(), CurrentUser.id().orElse(null))) {
            throw new BusinessRuleException("OWN_REQUEST", "You requested this change, so someone else must decide it");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new BusinessRuleException("REASON_REQUIRED", "Give a reason for rejecting the request");
        }
        c.setState(approve ? StatusChange.State.APPROVED : StatusChange.State.REJECTED);
        c.setDecidedById(CurrentUser.id().orElse(null));
        c.setDecidedByName(CurrentUser.name());
        c.setDecidedAt(OffsetDateTime.now());
        c.setDecisionNote(blankToNull(note));
        audit.record(approve ? "STATUS_CHANGE_APPROVED" : "STATUS_CHANGE_REJECTED", "Student", c.getStudent().getId(), details(c));
        if (approve && !c.getEffectiveDate().isAfter(today())) {
            apply(c);
        }
        return view(c);
    }

    @Transactional
    public StatusChangeView cancel(Long id, String note) {
        StatusChange c = change(id);
        if (!canCancel(c)) {
            if (!StatusChange.OPEN.contains(c.getState()) || c.getState() == StatusChange.State.APPROVED) throw stateError(c);
            throw new AccessDeniedException("Only Registry can cancel this request");
        }
        c.setState(StatusChange.State.CANCELLED);
        c.setDecisionNote(blankToNull(note));
        c.setDecidedByName(CurrentUser.name());
        c.setDecidedAt(OffsetDateTime.now());
        audit.record("STATUS_CHANGE_CANCELLED", "Student", c.getStudent().getId(), details(c));
        return view(c);
    }

    /** Applies approved changes whose effective date has come. Run each morning for every institution. */
    @Transactional
    public int applyDue() {
        int n = 0;
        for (StatusChange c : changes.findByStateAndEffectiveDateLessThanEqual(StatusChange.State.APPROVED, today())) {
            if (apply(c)) n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<StatusChangeView> forStudent(Long studentId) {
        student(studentId);
        return changes.findByStudentIdOrderByRequestedAtDesc(studentId).stream().map(this::view).toList();
    }

    /** Open requests (oldest first) or the latest 200 of any state, within the signed-in person's data scope. */
    @Transactional(readOnly = true)
    public List<StatusChangeView> list(boolean openOnly) {
        DataScope.Scope sc = scope.current();
        List<StatusChange> rows = openOnly ? changes.findByStateInOrderByRequestedAtAsc(StatusChange.OPEN) : changes.findTop200ByOrderByRequestedAtDesc();
        return rows.stream().filter(c -> sc.programme(c.getStudent().getProgramme())).map(this::view).toList();
    }

    /** Open requests waiting for the signed-in person (for the Approvals inbox). */
    @Transactional(readOnly = true)
    public List<StatusChangeView> waitingForMe() {
        return changes.findByStateInOrderByRequestedAtAsc(List.of(StatusChange.State.REQUESTED, StatusChange.State.RECOMMENDED)).stream()
                .filter(c -> canRecommend(c) || mayDecide(c)).map(this::view).toList();
    }

    private boolean apply(StatusChange c) {
        Student s = c.getStudent();
        if (s.getStatus() != c.getFromStatus()) {
            c.setState(StatusChange.State.CANCELLED);
            c.setDecisionNote("Not applied: the student's status changed to " + s.getStatus().name() + " before the effective date");
            audit.record("STATUS_CHANGE_CANCELLED", "Student", s.getId(), details(c));
            log.warn("Status change {} for {} not applied: status is now {}", c.getId(), s.getRegistrationNo(), s.getStatus());
            return false;
        }
        s.setStatus(c.getToStatus());
        c.setState(StatusChange.State.APPLIED);
        c.setAppliedAt(OffsetDateTime.now());
        Map<String, Object> d = details(c);
        audit.record("STUDENT_STATUS_CHANGED", "Student", s.getId(), d);
        events.publishEvent(new StudentStatusAppliedEvent(c.getId(), s.getId(), c.getType(), c.getEffectiveDate(),
                c.getReference() == null ? "Status change " + c.getId() : c.getReference(), c.getDecidedById(), c.getDecidedByName()));
        if (c.getType().ends()) {
            idCards.revokeAll(s, "Studies ended: " + label(c.getType()) + " effective " + DAY.format(c.getEffectiveDate()));
        }
        if (s.getPhone() != null && !s.getPhone().isBlank()) {
            String institution = tenants.findById(TenantContext.requireTenantId()).map(t -> t.getName()).orElse("");
            messaging.event("STUDENT_STATUS_CHANGED", s.getPhone(), Map.of("institution", institution, "change", c.getType().verb,
                    "date", DAY.format(c.getEffectiveDate()), "registrationNo", s.getRegistrationNo() == null ? "" : s.getRegistrationNo()));
        }
        return true;
    }

    private boolean canRecommend(StatusChange c) {
        if (c.getState() != StatusChange.State.REQUESTED) return false;
        Department d = c.getStudent().getProgramme().getDepartment();
        return d != null && access.headsDepartment(d.getId());
    }

    private boolean mayDecide(StatusChange c) {
        return (c.getState() == StatusChange.State.REQUESTED || c.getState() == StatusChange.State.RECOMMENDED)
                && access.has(Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC)
                && !Objects.equals(c.getRequestedById(), CurrentUser.id().orElse(null));
    }

    private boolean canCancel(StatusChange c) {
        return (c.getState() == StatusChange.State.REQUESTED || c.getState() == StatusChange.State.RECOMMENDED)
                && access.has(Role.INSTITUTION_ADMIN, Role.REGISTRAR);
    }

    StatusChangeView view(StatusChange c) {
        Student s = c.getStudent();
        Department d = s.getProgramme().getDepartment();
        return new StatusChangeView(c.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(),
                d == null ? null : d.getName(), c.getType(), c.getFromStatus().name(), c.getToStatus().name(), s.getStatus().name(),
                c.getReason(), c.getReference(), c.getEffectiveDate(), c.getUntilDate(), c.getState(), c.getRequestedByName(),
                c.getRequestedAt(), c.getRecommendedByName(), c.getRecommendedAt(), c.getRecommendationNote(), c.getDecidedByName(),
                c.getDecidedAt(), c.getDecisionNote(), c.getAppliedAt(), canRecommend(c), mayDecide(c), canCancel(c));
    }

    private Map<String, Object> details(StatusChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change", c.getType().name());
        m.put("registrationNo", c.getStudent().getRegistrationNo());
        m.put("from", c.getFromStatus().name());
        m.put("to", c.getToStatus().name());
        m.put("effective", c.getEffectiveDate().toString());
        if (c.getUntilDate() != null) m.put("until", c.getUntilDate().toString());
        m.put("state", c.getState().name());
        m.put("requestId", c.getId());
        return m;
    }

    private Student student(Long id) {
        Student s = students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
        scope.requireProgramme(s.getProgramme().getId());
        return s;
    }

    private StatusChange change(Long id) {
        StatusChange c = changes.findById(id).orElseThrow(() -> new NotFoundException("Status change", id));
        scope.requireProgramme(c.getStudent().getProgramme().getId());
        return c;
    }

    private static BusinessRuleException stateError(StatusChange c) {
        return new BusinessRuleException("INVALID_STATE", "This request is already " + c.getState().name().toLowerCase(Locale.ROOT));
    }

    static String label(StatusChange.Type t) {
        return t.name().charAt(0) + t.name().substring(1).toLowerCase(Locale.ROOT);
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
