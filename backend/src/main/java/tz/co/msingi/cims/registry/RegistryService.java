package tz.co.msingi.cims.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.letter.AdmissionLetter;
import tz.co.msingi.cims.admission.letter.AdmissionLetterRepository;
import tz.co.msingi.cims.admission.letter.JoiningInstructions;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.PageResponse;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryDtos.ChecklistRequest;
import tz.co.msingi.cims.registry.RegistryDtos.FirstRegistrationView;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverOverview;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverResult;
import tz.co.msingi.cims.registry.RegistryDtos.HandoverRow;
import tz.co.msingi.cims.registry.RegistryDtos.SettingsView;
import tz.co.msingi.cims.registry.RegistryDtos.StudentDetail;
import tz.co.msingi.cims.registry.RegistryDtos.StudentSummary;
import tz.co.msingi.cims.registry.RegistryRepositories.FirstRegistrationRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registration hand-over (BP-05 step 1, FR-REG-01/02): accepted applicants become students with a registration
 * number, without re-entering data. First registration (steps 4–5): originals checked at reporting, then approved.
 */
@Service
@RequiredArgsConstructor
public class RegistryService {

    /** Used when a window has no joining instructions listing documents to bring. */
    static final List<String> DEFAULT_DOCUMENTS = List.of("Original academic certificates", "Original birth certificate");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final StudentRepository students;
    private final FirstRegistrationRepository firstRegistrations;
    private final ApplicationRepository applications;
    private final AdmissionWindowRepository windows;
    private final AdmissionLetterRepository letters;
    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final NumberSequenceService sequences;
    private final Messaging messaging;
    private final AuditService audit;
    private final AnnualBillingService annualBilling;
    private final AccountService accounts;

    // ------------------------------------------------------------------ settings (FR-REG-02)

    @Transactional(readOnly = true)
    public SettingsView settings() {
        Tenant t = currentTenant();
        String p = t.getRegistrationNoPattern() == null ? RegistrationNumberPattern.DEFAULT : t.getRegistrationNoPattern();
        return new SettingsView(p, t.getRegistrationNoPattern() == null, example(RegistrationNumberPattern.parse(p), t));
    }

    @Transactional(readOnly = true)
    public SettingsView preview(String pattern) {
        return new SettingsView(pattern.trim(), false, example(parse(pattern), currentTenant()));
    }

    /** Changes apply to new students only: existing registration numbers never change (BRU-06). */
    @Transactional
    public SettingsView updatePattern(String pattern) {
        RegistrationNumberPattern rp = parse(pattern);
        Tenant t = currentTenant();
        String before = t.getRegistrationNoPattern();
        t.setRegistrationNoPattern(rp.pattern());
        tenants.save(t);
        audit.record("REGISTRATION_NO_PATTERN_SET", "Tenant", t.getId(),
                Map.of("from", before == null ? RegistrationNumberPattern.DEFAULT : before, "to", rp.pattern()));
        return settings();
    }

    private static RegistrationNumberPattern parse(String pattern) {
        try {
            return RegistrationNumberPattern.parse(pattern);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_PATTERN", e.getMessage());
        }
    }

    private static String example(RegistrationNumberPattern p, Tenant t) {
        return p.render(new RegistrationNumberPattern.Values(t.getCode(), "ODIT", "NTA", 6, 2027, "SEPTEMBER"), 1);
    }

    // ------------------------------------------------------------------ hand-over

    @Transactional(readOnly = true)
    public HandoverOverview handoverOverview(Long windowId) {
        AdmissionWindow w = requireWindow(windowId);
        List<Application> accepted = applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(windowId, Set.of(Application.Status.ACCEPTED));
        List<HandoverRow> rows = rows(accepted);
        long handed = rows.stream().filter(r -> r.studentId() != null).count();
        SettingsView settings = settings();
        return new HandoverOverview(windowId, w.getName(), accepted.size(), handed, accepted.size() - handed,
                settings.registrationNoPattern(), settings.example(), rows);
    }

    /**
     * Creates student records for accepted applicants of the window (all not yet handed over, or the ones given).
     * Only applicants who accepted the offer qualify: that implies NACTVET verification and, for multiple
     * admissions, confirmation of this institution (BRU-04, BRU-05).
     */
    @Transactional
    public HandoverResult handover(Long windowId, List<Long> applicationIds) {
        AdmissionWindow w = requireWindow(windowId);
        List<Application> accepted = applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(windowId, Set.of(Application.Status.ACCEPTED));
        Set<Long> already = students.findByApplicationIdIn(accepted.stream().map(Application::getId).toList()).stream()
                .map(Student::getApplicationId).collect(Collectors.toSet());
        List<Application> targets;
        if (applicationIds == null || applicationIds.isEmpty()) {
            targets = accepted.stream().filter(a -> !already.contains(a.getId())).toList();
        } else {
            Map<Long, Application> byId = accepted.stream().collect(Collectors.toMap(Application::getId, Function.identity()));
            targets = new ArrayList<>();
            for (Long id : new LinkedHashSet<>(applicationIds)) {
                Application a = byId.get(id);
                if (a == null) {
                    throw new BusinessRuleException("NOT_ACCEPTED", "Application " + id + " is not an accepted offer in this window");
                }
                if (already.contains(id)) {
                    throw new BusinessRuleException("ALREADY_HANDED_OVER", a.getReferenceNo() + " already has a student record");
                }
                targets.add(a);
            }
        }
        if (targets.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_HAND_OVER", "Every accepted applicant already has a student record");
        }
        Tenant tenant = currentTenant();
        RegistrationNumberPattern pattern = RegistrationNumberPattern.parse(
                tenant.getRegistrationNoPattern() == null ? RegistrationNumberPattern.DEFAULT : tenant.getRegistrationNoPattern());
        List<Student> created = new ArrayList<>();
        for (Application a : targets) {
            created.add(createStudent(a, w, tenant, pattern));
        }
        audit.record("STUDENTS_HANDED_OVER", "AdmissionWindow", windowId, Map.of("count", created.size(),
                "first", created.get(0).getRegistrationNo(), "last", created.get(created.size() - 1).getRegistrationNo()));
        return new HandoverResult(created.size(), rows(targets));
    }

    private Student createStudent(Application app, AdmissionWindow w, Tenant tenant, RegistrationNumberPattern pattern) {
        Applicant ap = app.getApplicant();
        var programme = app.getSelectedWindowProgramme().getProgramme();
        Student s = new Student();
        s.setApplicantId(ap.getId());
        s.setApplicationId(app.getId());
        s.setUserId(ap.getUser().getId());
        s.setRegistrationNo(nextRegistrationNo(pattern, new RegistrationNumberPattern.Values(tenant.getCode(), programme.getCode(),
                programme.getAwardFamily().name(), programme.getAwardLevel(), w.getAcademicYear().getStartDate().getYear(), w.getIntake().getCode())));
        s.setFirstName(ap.getFirstName());
        s.setMiddleName(ap.getMiddleName());
        s.setSurname(ap.getSurname());
        s.setSex(ap.getSex());
        s.setDateOfBirth(ap.getDateOfBirth());
        s.setNationality(ap.getNationality());
        s.setPhone(ap.getPhone());
        s.setEmail(ap.getEmail());
        s.setRegion(ap.getRegion());
        s.setDistrict(ap.getDistrict());
        s.setPostalAddress(ap.getPostalAddress());
        s.setDisability(ap.getDisability());
        s.setNextOfKinName(ap.getNextOfKinName());
        s.setNextOfKinPhone(ap.getNextOfKinPhone());
        s.setNextOfKinRelation(ap.getNextOfKinRelation());
        s.setProgramme(programme);
        s.setEntryAcademicYear(w.getAcademicYear());
        s.setIntake(w.getIntake());
        s.setNactvetVerificationNo(app.getNactvetVerificationNo());
        s.setAdmissionLetterNo(letters.findByApplicationIdAndStatus(app.getId(), AdmissionLetter.Status.VALID)
                .map(AdmissionLetter::getLetterNo).orElse(null));
        s.setAdmittedAt(OffsetDateTime.now());
        s.setAdmittedByName(CurrentUser.name());
        students.save(s);

        JoiningInstructions j = w.getJoiningInstructions();
        List<String> docs = j == null || j.documentsToBring().isEmpty() ? DEFAULT_DOCUMENTS : j.documentsToBring();
        FirstRegistration f = new FirstRegistration();
        f.setStudent(s);
        f.setDocuments(docs.stream().map(d -> new FirstRegistration.DocumentCheck(d, false)).collect(Collectors.toCollection(ArrayList::new)));
        firstRegistrations.save(f);

        // fees: the invoice raised at acceptance moves to the student; bill now if it was not raised yet (BP-06)
        annualBilling.transferToStudent(s);
        annualBilling.billStudent(s, w.getAcademicYear());

        // the applicant account becomes the student's sign-in for the student portal, by registration number or phone
        users.findById(ap.getUser().getId()).ifPresent(u -> {
            u.grant(Role.STUDENT);
            if (!users.existsByUsernameIgnoreCase(s.getRegistrationNo())) u.setUsername(s.getRegistrationNo());
        });

        if (j == null) {
            messaging.event("STUDENT_WELCOME_NO_DATE", s.getPhone(), Map.of("institution", tenant.getName(), "registrationNo", s.getRegistrationNo()));
        } else {
            messaging.event("STUDENT_WELCOME", s.getPhone(), Map.of("institution", tenant.getName(), "registrationNo", s.getRegistrationNo(),
                    "reportingDate", j.reportingDate(), "venue", j.reportingVenue()));
        }
        audit.record("STUDENT_ADMITTED", "Student", s.getId(), Map.of("registrationNo", s.getRegistrationNo(), "application", app.getReferenceNo()));
        return s;
    }

    /** Next free number for the prefix; skips numbers taken under an earlier pattern so a number is never reused. */
    private String nextRegistrationNo(RegistrationNumberPattern pattern, RegistrationNumberPattern.Values v) {
        String sequenceName = "REGNO:" + pattern.prefix(v);
        for (int attempt = 0; attempt < 1000; attempt++) {
            String candidate = pattern.render(v, sequences.next(sequenceName));
            if (!students.existsByRegistrationNo(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free registration number for " + sequenceName);
    }

    private List<HandoverRow> rows(List<Application> apps) {
        Map<Long, Student> byApp = students.findByApplicationIdIn(apps.stream().map(Application::getId).toList()).stream()
                .collect(Collectors.toMap(Student::getApplicationId, Function.identity()));
        Map<Long, String> letterNos = letters.findByApplicationIdInAndStatus(apps.stream().map(Application::getId).toList(),
                AdmissionLetter.Status.VALID).stream().collect(Collectors.toMap(l -> l.getApplication().getId(), AdmissionLetter::getLetterNo));
        return apps.stream().map(a -> {
            Student s = byApp.get(a.getId());
            return new HandoverRow(a.getId(), a.getReferenceNo(), a.getApplicant().fullName(),
                    a.getSelectedWindowProgramme() == null ? null : a.getSelectedWindowProgramme().getProgramme().getName(),
                    letterNos.get(a.getId()), a.getOfferRespondedAt(), s == null ? null : s.getId(),
                    s == null ? null : s.getRegistrationNo(), s == null ? null : s.getStatus().name());
        }).toList();
    }

    // ------------------------------------------------------------------ students

    @Transactional(readOnly = true)
    public PageResponse<StudentSummary> search(Long programmeId, Student.Status status, String q, int page, int size) {
        Page<Student> p = students.search(programmeId, status, q == null || q.isBlank() ? null : q.trim(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("registrationNo")));
        return PageResponse.of(p, StudentSummary::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Student.Status s : Student.Status.values()) out.put(s.name(), 0L);
        students.countByStatus().forEach(r -> out.put(((Student.Status) r[0]).name(), ((Number) r[1]).longValue()));
        return out;
    }

    @Transactional(readOnly = true)
    public StudentDetail detail(Long studentId) {
        return detail(requireStudent(studentId));
    }

    private StudentDetail detail(Student s) {
        String ref = s.getApplicationId() == null ? null : applications.findById(s.getApplicationId()).map(Application::getReferenceNo).orElse(null);
        return new StudentDetail(StudentSummary.of(s), s.getFirstName(), s.getMiddleName(), s.getSurname(), s.getDateOfBirth(),
                s.getNationality(), s.getEmail(), s.getRegion(), s.getDistrict(), s.getPostalAddress(), s.getDisability(),
                s.getNextOfKinName(), s.getNextOfKinPhone(), s.getNextOfKinRelation(), s.getProgramme().getAwardName(), ref,
                s.getNactvetVerificationNo(), s.getAdmissionLetterNo(), s.getAdmittedAt(), s.getAdmittedByName(),
                FirstRegistrationView.of(firstRegistrations.findByStudentId(s.getId()).orElse(null)));
    }

    /** Class list for a programme (CSV): registered (active) students, or all non-exited ones. */
    @Transactional
    public String classList(Long programmeId, boolean activeOnly) {
        Set<Student.Status> statuses = activeOnly ? Set.of(Student.Status.ACTIVE) : Set.of(Student.Status.ADMITTED, Student.Status.ACTIVE);
        StringBuilder sb = new StringBuilder(Csv.line("S/N", "Registration No", "Name", "Sex", "Programme", "Year of study", "Phone", "Status"));
        int n = 0;
        for (Student s : students.findByProgrammeIdAndStatusInOrderBySurnameAscFirstNameAsc(programmeId, statuses)) {
            sb.append(Csv.line(++n, s.getRegistrationNo(), s.getSurname().toUpperCase(Locale.ROOT) + ", " + s.getFirstName()
                    + (s.getMiddleName() == null ? "" : " " + s.getMiddleName()), s.getSex(), s.getProgramme().getName(),
                    s.getYearOfStudy(), s.getPhone(), s.getStatus().name()));
        }
        audit.record("CLASS_LIST_EXPORTED", "Programme", programmeId, Map.of("students", n, "activeOnly", activeOnly));
        return sb.toString();
    }

    // ------------------------------------------------------------------ first registration (BP-05 steps 4–5)

    /** Records which originals were seen when the student reported; can be saved as often as needed until approval. */
    @Transactional
    public StudentDetail saveChecklist(Long studentId, ChecklistRequest req) {
        Student s = requireStudent(studentId);
        FirstRegistration f = requirePendingRegistration(s);
        Map<String, Boolean> given = new LinkedHashMap<>();
        req.documents().forEach(d -> given.put(d.item().trim(), d.verified()));
        Set<String> known = f.getDocuments().stream().map(FirstRegistration.DocumentCheck::item).collect(Collectors.toSet());
        for (String item : given.keySet()) {
            if (!known.contains(item)) {
                throw new BusinessRuleException("UNKNOWN_DOCUMENT", "'" + item + "' is not on this student's checklist");
            }
        }
        f.setDocuments(f.getDocuments().stream()
                .map(d -> new FirstRegistration.DocumentCheck(d.item(), given.getOrDefault(d.item(), d.verified())))
                .collect(Collectors.toCollection(ArrayList::new)));
        if (f.getReportedAt() == null) f.setReportedAt(OffsetDateTime.now());
        f.setRemarks(req.remarks() == null || req.remarks().isBlank() ? null : req.remarks().trim());
        f.setCheckedByName(CurrentUser.name());
        audit.record("FIRST_REGISTRATION_CHECKED", "Student", s.getId(), Map.of("verified",
                f.getDocuments().stream().filter(FirstRegistration.DocumentCheck::verified).count(), "of", f.getDocuments().size()));
        return detail(s);
    }

    /** Registry approval: the student becomes ACTIVE (BRU-04). All originals on the checklist must have been seen. */
    @Transactional
    public StudentDetail approveFirstRegistration(Long studentId) {
        Student s = requireStudent(studentId);
        FirstRegistration f = requirePendingRegistration(s);
        List<String> outstanding = f.getDocuments().stream().filter(d -> !d.verified()).map(FirstRegistration.DocumentCheck::item).toList();
        if (!outstanding.isEmpty()) {
            throw new BusinessRuleException("DOCUMENTS_OUTSTANDING", "Originals not yet verified: " + String.join(", ", outstanding));
        }
        if (s.getStatus() != Student.Status.ADMITTED) {
            throw new BusinessRuleException("INVALID_STATUS", "Only admitted students can complete first registration");
        }
        accounts.requireCleared(s, ClearanceGate.Gate.REGISTRATION); // FR-FIN-06 registration gate
        f.setStatus(FirstRegistration.Status.APPROVED);
        f.setApprovedAt(OffsetDateTime.now());
        f.setApprovedBy(CurrentUser.requireId());
        f.setApprovedByName(CurrentUser.name());
        s.setStatus(Student.Status.ACTIVE);
        audit.record("FIRST_REGISTRATION_APPROVED", "Student", s.getId(), Map.of("registrationNo", s.getRegistrationNo()));
        messaging.event("REGISTRATION_COMPLETE", s.getPhone(), Map.of("institution", currentTenant().getName(), "registrationNo", s.getRegistrationNo()));
        return detail(s);
    }

    // ------------------------------------------------------------------ helpers

    private FirstRegistration requirePendingRegistration(Student s) {
        FirstRegistration f = firstRegistrations.findByStudentId(s.getId())
                .orElseThrow(() -> new BusinessRuleException("NO_FIRST_REGISTRATION", "This student has no first registration record"));
        if (f.getStatus() == FirstRegistration.Status.APPROVED) {
            throw new BusinessRuleException("ALREADY_REGISTERED", "First registration was already approved");
        }
        return f;
    }

    private Student requireStudent(Long id) {
        return students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
    }

    private AdmissionWindow requireWindow(Long id) {
        return windows.findById(id).orElseThrow(() -> new NotFoundException("Admission window", id));
    }

    private Tenant currentTenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }
}
