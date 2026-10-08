package tz.co.msingi.cims.portal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.portal.PortalDtos.ChangeRequestView;
import tz.co.msingi.cims.portal.PortalDtos.ChangeView;
import tz.co.msingi.cims.portal.PortalRepositories.StudentChangeRequestRepository;
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Requests to correct a student's personal details (FR-REG-08): submitted by the student, decided by Registry, audited. */
@Service
@RequiredArgsConstructor
public class ChangeRequestService {

    private final StudentChangeRequestRepository requests;
    private final UserAccountRepository users;
    private final Messaging messaging;
    private final AuditService audit;

    // ------------------------------------------------------------------ student side

    @Transactional
    public ChangeRequestView submit(Student s, Map<String, String> proposed, String reason) {
        if (requests.findByStudentIdAndStatus(s.getId(), StudentChangeRequest.Status.PENDING).isPresent()) {
            throw new BusinessRuleException("PENDING_REQUEST_EXISTS", "You already have a request waiting for Registry. Cancel it to send a new one.");
        }
        Map<String, StudentChangeRequest.Change> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : proposed.entrySet()) {
            String to = StudentFields.normalise(e.getKey(), e.getValue());
            String from = StudentFields.FIELDS.get(e.getKey()).get().apply(s);
            if (!Objects.equals(blankToNull(from), to)) changes.put(e.getKey(), new StudentChangeRequest.Change(from, to));
        }
        if (changes.isEmpty()) throw new BusinessRuleException("NO_CHANGES", "Nothing differs from your current details");
        StudentChangeRequest r = new StudentChangeRequest();
        r.setStudent(s);
        r.setChanges(changes);
        r.setReason(reason.trim());
        r.setNeedsDocuments(changes.keySet().stream().anyMatch(StudentFields.DOCUMENTED::contains));
        r.setSubmittedAt(OffsetDateTime.now());
        requests.save(r);
        audit.record("STUDENT_CHANGE_REQUESTED", "Student", s.getId(), Map.of("fields", String.join(",", changes.keySet())));
        return view(r);
    }

    @Transactional
    public ChangeRequestView cancel(Student s, Long requestId) {
        StudentChangeRequest r = requests.findById(requestId).filter(x -> x.getStudent().getId().equals(s.getId()))
                .orElseThrow(() -> new NotFoundException("Request", requestId));
        if (r.getStatus() != StudentChangeRequest.Status.PENDING) throw new BusinessRuleException("NOT_PENDING", "This request has already been decided");
        r.setStatus(StudentChangeRequest.Status.CANCELLED);
        audit.record("STUDENT_CHANGE_CANCELLED", "Student", s.getId(), Map.of("request", r.getId()));
        return view(r);
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestView> forStudent(Long studentId) {
        return requests.findByStudentIdOrderBySubmittedAtDesc(studentId).stream().map(ChangeRequestService::view).toList();
    }

    @Transactional(readOnly = true)
    public ChangeRequestView pendingFor(Long studentId) {
        return requests.findByStudentIdAndStatus(studentId, StudentChangeRequest.Status.PENDING).map(ChangeRequestService::view).orElse(null);
    }

    // ------------------------------------------------------------------ Registry side

    @Transactional(readOnly = true)
    public List<ChangeRequestView> list(String status) {
        if ("DECIDED".equalsIgnoreCase(status)) {
            return requests.findTop50ByStatusNotOrderByDecidedAtDesc(StudentChangeRequest.Status.PENDING).stream()
                    .filter(r -> r.getStatus() != StudentChangeRequest.Status.CANCELLED).map(ChangeRequestService::view).toList();
        }
        return requests.findByStatusOrderBySubmittedAtAsc(StudentChangeRequest.Status.PENDING).stream().map(ChangeRequestService::view).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return requests.countByStatus(StudentChangeRequest.Status.PENDING);
    }

    /** Applies the changes to the student record; phone, email and name also update the sign-in account. */
    @Transactional
    public ChangeRequestView approve(Long id, String note) {
        StudentChangeRequest r = requirePending(id);
        Student s = r.getStudent();
        Map<String, Object> before = new LinkedHashMap<>();
        r.getChanges().forEach((field, c) -> {
            StudentFields.Field f = StudentFields.FIELDS.get(field);
            before.put(field, String.valueOf(f.get().apply(s)));
            f.set().accept(s, c.to());
        });
        if (s.getUserId() != null) {
            var user = users.findById(s.getUserId()).orElse(null);
            if (user != null) {
                if (r.getChanges().containsKey("phone")) {
                    if (users.existsByPhoneAndIdNot(s.getPhone(), user.getId())) {
                        throw new BusinessRuleException("PHONE_IN_USE", "Another account already uses " + s.getPhone() + ". Reject the request or ask the student for another number.");
                    }
                    user.setPhone(s.getPhone());
                }
                if (r.getChanges().containsKey("email") && s.getEmail() != null) {
                    if (users.existsByEmailIgnoreCaseAndIdNot(s.getEmail(), user.getId())) {
                        throw new BusinessRuleException("EMAIL_IN_USE", "Another account already uses " + s.getEmail());
                    }
                    user.setEmail(s.getEmail());
                } else if (r.getChanges().containsKey("email")) {
                    user.setEmail(null);
                }
                if (r.getChanges().keySet().stream().anyMatch(java.util.Set.of("firstName", "middleName", "surname")::contains)) user.setFullName(s.fullName());
            }
        }
        decide(r, StudentChangeRequest.Status.APPROVED, note);
        audit.record("STUDENT_CHANGE_APPROVED", "Student", s.getId(), Map.of("request", r.getId(), "before", before,
                "fields", String.join(",", r.getChanges().keySet())));
        messaging.event("DETAILS_CHANGE_APPROVED", s.getPhone(), Map.of());
        return view(r);
    }

    @Transactional
    public ChangeRequestView reject(Long id, String note) {
        StudentChangeRequest r = requirePending(id);
        decide(r, StudentChangeRequest.Status.REJECTED, note);
        audit.record("STUDENT_CHANGE_REJECTED", "Student", r.getStudent().getId(), Map.of("request", r.getId(), "note", note.trim()));
        messaging.event("DETAILS_CHANGE_REJECTED", r.getStudent().getPhone(), Map.of("reason", note.trim()));
        return view(r);
    }

    private void decide(StudentChangeRequest r, StudentChangeRequest.Status status, String note) {
        r.setStatus(status);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecidedByName(CurrentUser.name());
        r.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
    }

    private StudentChangeRequest requirePending(Long id) {
        StudentChangeRequest r = requests.findById(id).orElseThrow(() -> new NotFoundException("Request", id));
        if (r.getStatus() != StudentChangeRequest.Status.PENDING) throw new BusinessRuleException("NOT_PENDING", "This request has already been decided");
        return r;
    }

    static ChangeRequestView view(StudentChangeRequest r) {
        Student s = r.getStudent();
        List<ChangeView> changes = r.getChanges().entrySet().stream()
                .map(e -> new ChangeView(e.getKey(), StudentFields.label(e.getKey()), e.getValue().from(), e.getValue().to())).toList();
        return new ChangeRequestView(r.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getName(), r.getStatus().name(),
                changes, r.getReason(), r.isNeedsDocuments(), r.getSubmittedAt(), r.getDecidedAt(), r.getDecidedByName(), r.getDecisionNote());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
