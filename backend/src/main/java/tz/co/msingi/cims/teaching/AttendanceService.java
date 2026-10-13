package tz.co.msingi.cims.teaching;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRegistrationQueries;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ExamSettings;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.exams.OfferingStaff;
import tz.co.msingi.cims.exams.RegulationService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistrationRepository;
import tz.co.msingi.cims.teaching.TeachingDtos.AttendanceView;
import tz.co.msingi.cims.teaching.TeachingDtos.Mark;
import tz.co.msingi.cims.teaching.TeachingDtos.MyAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.MyModuleAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.OfferingAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.RegisterLine;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionRequest;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionSummary;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionView;
import tz.co.msingi.cims.teaching.TeachingDtos.StudentAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.Workload;
import tz.co.msingi.cims.teaching.TeachingDtos.WorkloadModule;
import tz.co.msingi.cims.teaching.TeachingDtos.WorkloadView;
import tz.co.msingi.cims.teaching.TeachingRepositories.AttendanceAlertRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.AttendanceRecordRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.ClassSessionRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.ExamEligibilityRepository;

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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Class lists, class sessions and attendance (FR-TCH-02/03/04, BP-07 steps 2-3): lecturers record who attended each session,
 * in bulk and tolerant of a device that saves the same session twice; the system keeps each student's percentage, warns students
 * who fall below the examination threshold and shows Heads of Department who is at risk. Also the lecturer workload view.
 */
@Service
@RequiredArgsConstructor
public class AttendanceService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final SemesterRegistration.Status APPROVED = SemesterRegistration.Status.APPROVED;

    private final ClassSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final AttendanceAlertRepository alerts;
    private final ExamEligibilityRepository eligibility;
    private final ModuleOfferingRepository offerings;
    private final ExamRegistrationQueries registrations;
    private final SemesterRegistrationRepository semesterRegistrations;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final RegulationService regulations;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    // ------------------------------------------------------------------ an offering's attendance

    @Transactional(readOnly = true)
    public AttendanceView view(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        requireRead(o);
        ExamSettings settings = regulations.settings();
        BigDecimal threshold = settings.getMinAttendancePercent();
        List<ModuleRegistration> regs = classList(o);
        List<ClassSession> list = sessions.findByOfferingIdOrderBySessionDateDescStartTimeDesc(o.getId());
        List<AttendanceRecord> recs = records.findByOffering(o.getId());
        Map<Long, Tally> tallies = Tally.byRegistration(recs);
        Map<Long, OffsetDateTime> alerted = alerts.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(AttendanceAlert::getModuleRegistrationId, AttendanceAlert::getAlertedAt, (a, b) -> a));
        Map<Long, ExamEligibility> elig = eligibility.findByOfferingId(o.getId()).stream()
                .collect(Collectors.toMap(ExamEligibility::getModuleRegistrationId, Function.identity()));
        Map<Long, Map<AttendanceRecord.Status, Integer>> perSession = new HashMap<>();
        Map<Long, Integer> selfPerSession = new HashMap<>();
        for (AttendanceRecord r : recs) {
            perSession.computeIfAbsent(r.getSession().getId(), k -> new HashMap<>()).merge(r.getStatus(), 1, Integer::sum);
            if (r.getCheckedInAt() != null) selfPerSession.merge(r.getSession().getId(), 1, Integer::sum);
        }

        List<StudentAttendance> rows = regs.stream().map(m -> {
            Student s = m.getRegistration().getStudent();
            Tally t = tallies.getOrDefault(m.getId(), Tally.NONE);
            ExamEligibility e = elig.get(m.getId());
            return new StudentAttendance(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), t.counted(),
                    t.attended(), t.absent(), t.excused(), t.percent(), t.below(threshold), alerted.get(m.getId()),
                    e == null ? null : e.isEligible(), e != null && e.isExempted());
        }).toList();
        Set<Long> inClass = regs.stream().map(ModuleRegistration::getId).collect(Collectors.toSet());
        List<Tally> classTallies = tallies.entrySet().stream().filter(en -> inClass.contains(en.getKey())).map(Map.Entry::getValue).toList();
        return new AttendanceView(o.getId(), o.getSemester().getId(), o.getSemester().getName(), o.getModule().getCode(), o.getModule().getTitle(),
                o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(), o.getStatus().name(), lecturerNames(o),
                threshold, list.size(), list.stream().mapToInt(ClassSession::getDurationMinutes).sum(), Tally.average(classTallies),
                (int) rows.stream().filter(StudentAttendance::below).count(), canRecord(o),
                list.stream().map(cs -> summary(cs, perSession.getOrDefault(cs.getId(), Map.of()), selfPerSession.getOrDefault(cs.getId(), 0))).toList(), rows);
    }

    @Transactional(readOnly = true)
    public SessionView session(Long sessionId) {
        ClassSession cs = require(sessionId);
        requireRead(cs.getOffering());
        return sessionView(cs);
    }

    /** A blank register for a new session: the class list, everyone marked present. */
    @Transactional(readOnly = true)
    public List<RegisterLine> register(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        requireRead(o);
        return classList(o).stream().map(m -> line(m, AttendanceRecord.Status.PRESENT.name(), null)).toList();
    }

    // ------------------------------------------------------------------ recording (FR-TCH-03)

    /**
     * Records a session and its attendance. A device that retries the same save (same client reference) gets the session it already
     * recorded instead of a duplicate, so a lecturer on an unreliable connection can simply try again.
     */
    @Transactional
    public SessionView record(Long offeringId, SessionRequest req) {
        ModuleOffering o = offering(offeringId);
        UUID ref = req.clientRef() == null ? UUID.randomUUID() : req.clientRef();
        ClassSession existing = sessions.findByClientRef(ref).orElse(null);
        if (existing != null) {
            if (!existing.getOffering().getId().equals(o.getId())) throw new BusinessRuleException("DUPLICATE_REFERENCE", "That session reference is already used");
            return sessionView(existing);
        }
        requireRecorder(o);
        checkDate(o.getSemester(), req.sessionDate());
        if (sessions.existsByOfferingIdAndSessionDateAndStartTime(o.getId(), req.sessionDate(), req.startTime())) {
            throw new BusinessRuleException("SESSION_EXISTS", "A session of " + o.getModule().getCode() + " is already recorded for " + req.sessionDate()
                    + " at " + req.startTime() + ". Open it to make changes.");
        }
        ClassSession cs = new ClassSession();
        cs.setOffering(o);
        cs.setClientRef(ref);
        cs.setRecordedById(access.me());
        cs.setRecordedByName(CurrentUser.name());
        header(cs, req);
        applyMarks(o, cs, req.marks());
        sessions.saveAndFlush(cs);
        audit.record("ATTENDANCE_RECORDED", "ClassSession", cs.getId(), Map.of("module", o.getModule().getCode(), "date", req.sessionDate().toString(),
                "students", cs.getRecords().size()));
        warnLowAttendance(o);
        return sessionView(cs);
    }

    @Transactional
    public SessionView update(Long sessionId, SessionRequest req) {
        ClassSession cs = require(sessionId);
        ModuleOffering o = cs.getOffering();
        requireRecorder(o);
        if (cs.checkInOpen()) {
            throw new BusinessRuleException("CHECKIN_OPEN", "Self check-in for this session is still open. Close it first, then correct the register.");
        }
        checkDate(o.getSemester(), req.sessionDate());
        boolean slotChanged = !cs.getSessionDate().equals(req.sessionDate()) || !cs.getStartTime().equals(req.startTime());
        if (slotChanged && sessions.existsByOfferingIdAndSessionDateAndStartTime(o.getId(), req.sessionDate(), req.startTime())) {
            throw new BusinessRuleException("SESSION_EXISTS", "Another session is already recorded at that date and time");
        }
        header(cs, req);
        applyMarks(o, cs, req.marks());
        sessions.saveAndFlush(cs);
        audit.record("ATTENDANCE_UPDATED", "ClassSession", cs.getId(), Map.of("module", o.getModule().getCode(), "date", req.sessionDate().toString()));
        warnLowAttendance(o);
        return sessionView(cs);
    }

    @Transactional
    public void delete(Long sessionId) {
        ClassSession cs = require(sessionId);
        requireRecorder(cs.getOffering());
        audit.record("ATTENDANCE_SESSION_DELETED", "ClassSession", cs.getId(), Map.of("module", cs.getOffering().getModule().getCode(),
                "date", cs.getSessionDate().toString()));
        sessions.delete(cs);
    }

    private static void header(ClassSession cs, SessionRequest req) {
        cs.setSessionDate(req.sessionDate());
        cs.setStartTime(req.startTime().withSecond(0).withNano(0));
        cs.setDurationMinutes(req.durationMinutes().shortValue());
        cs.setKind(req.kind());
        cs.setTopic(req.topic() == null || req.topic().isBlank() ? null : req.topic().trim());
    }

    /** Sets the register in place; students left out of the request are not recorded for this session. */
    private void applyMarks(ModuleOffering o, ClassSession cs, List<Mark> marks) {
        Map<Long, ModuleRegistration> regs = classList(o).stream().collect(Collectors.toMap(ModuleRegistration::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (Mark m : marks) {
            if (!regs.containsKey(m.moduleRegistrationId())) {
                throw new BusinessRuleException("NOT_IN_CLASS", "A student on this register is no longer in the class. Reload the class list and try again.");
            }
            if (!seen.add(m.moduleRegistrationId())) throw new BusinessRuleException("DUPLICATE_STUDENT", "A student appears twice on the register");
        }
        Map<Long, AttendanceRecord> current = cs.getRecords().stream().collect(Collectors.toMap(AttendanceRecord::getModuleRegistrationId, Function.identity()));
        cs.getRecords().removeIf(r -> !seen.contains(r.getModuleRegistrationId()));
        for (Mark m : marks) {
            AttendanceRecord r = current.get(m.moduleRegistrationId());
            if (r == null) {
                r = new AttendanceRecord();
                r.setSession(cs);
                r.setModuleRegistrationId(m.moduleRegistrationId());
                r.setStudentId(regs.get(m.moduleRegistrationId()).getStudentId());
                cs.getRecords().add(r);
            }
            if (r.getStatus() != null && r.getStatus() != m.status()) r.setSource(AttendanceRecord.Source.REGISTER);   // corrected by the lecturer
            r.setStatus(m.status());
            r.setNote(m.note() == null || m.note().isBlank() ? null : m.note().trim());
        }
    }

    static void checkDate(Semester sem, LocalDate date) {
        LocalDate today = LocalDate.now(ZONE);
        if (date.isAfter(today)) throw new BusinessRuleException("FUTURE_SESSION", "Attendance can be recorded only for sessions that have taken place");
        if (sem.getEndDate() != null && date.isAfter(sem.getEndDate())) {
            throw new BusinessRuleException("OUTSIDE_SEMESTER", sem.getName() + " ended on " + sem.getEndDate());
        }
        if (date.isBefore(today.minusDays(180))) throw new BusinessRuleException("OUTSIDE_SEMESTER", "That date is too far in the past");
    }

    /** Warns, once per module, each student whose attendance has fallen below the threshold (BP-07 step 3). */
    void warnLowAttendance(ModuleOffering o) {
        ExamSettings settings = regulations.settings();
        BigDecimal threshold = settings.getMinAttendancePercent();
        Map<Long, Tally> tallies = Tally.byRegistration(records.findByOffering(o.getId()));
        Set<Long> done = alerts.findByOfferingId(o.getId()).stream().map(AttendanceAlert::getModuleRegistrationId).collect(Collectors.toSet());
        List<ModuleRegistration> due = classList(o).stream().filter(m -> !done.contains(m.getId())).filter(m -> {
            Tally t = tallies.getOrDefault(m.getId(), Tally.NONE);
            return t.counted() >= settings.getAttendanceAlertAfterSessions() && t.below(threshold);
        }).toList();
        for (ModuleRegistration m : due) {
            Tally t = tallies.get(m.getId());
            AttendanceAlert a = new AttendanceAlert();
            a.setOfferingId(o.getId());
            a.setModuleRegistrationId(m.getId());
            a.setStudentId(m.getStudentId());
            a.setPercent(t.percent());
            alerts.save(a);
            Student s = m.getRegistration().getStudent();
            messaging.event("ATTENDANCE_LOW", s.getPhone(), Map.of("module", o.getModule().getCode(), "percent", pct(t.percent()),
                    "threshold", pct(threshold)));
        }
        if (!due.isEmpty()) {
            audit.record("LOW_ATTENDANCE_WARNED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "students", due.size()));
        }
    }

    // ------------------------------------------------------------------ summaries (BP-07 step 3) and workload (BP-07 step 1)

    /** Attendance per module offering of a semester: the lecturer's own, or every module the signed-in person oversees. */
    @Transactional(readOnly = true)
    public List<OfferingAttendance> summary(Long semesterId, boolean mineOnly) {
        Long me = access.me();
        BigDecimal threshold = regulations.settings().getMinAttendancePercent();
        List<ModuleOffering> visible = offerings.findBySemesterIdOrderByIdAsc(semesterId).stream()
                .filter(o -> isMine(o, me) || (!mineOnly && oversees(o))).toList();
        if (visible.isEmpty()) return List.of();
        Map<Long, List<AttendanceRecord>> recs = records.findByOfferings(visible.stream().map(ModuleOffering::getId).toList()).stream()
                .collect(Collectors.groupingBy(r -> r.getSession().getOffering().getId()));
        Map<Long, Object[]> totals = totals(semesterId);
        Map<Long, String> names = names(visible);
        return visible.stream().sorted(Comparator.comparing((ModuleOffering o) -> o.getModule().getCode())).map(o -> {
            List<ModuleRegistration> regs = classList(o);
            Set<Long> inClass = regs.stream().map(ModuleRegistration::getId).collect(Collectors.toSet());
            List<AttendanceRecord> mine = recs.getOrDefault(o.getId(), List.of());
            Map<Long, Tally> t = Tally.byRegistration(mine.stream().filter(r -> inClass.contains(r.getModuleRegistrationId())).toList());
            Object[] tot = totals.get(o.getId());
            LocalDate last = mine.stream().map(r -> r.getSession().getSessionDate()).max(Comparator.naturalOrder()).orElse(null);
            return new OfferingAttendance(o.getId(), o.getModule().getCode(), o.getModule().getTitle(),
                    o.getModule().getDepartment() == null ? null : o.getModule().getDepartment().getName(), lecturers(o, names), regs.size(),
                    tot == null ? 0 : ((Number) tot[1]).intValue(), tot == null ? 0 : ((Number) tot[2]).intValue(), Tally.average(t.values()),
                    (int) t.values().stream().filter(x -> x.below(threshold)).count(), isMine(o, me), last);
        }).toList();
    }

    /** Teaching load per lecturer for a semester: modules, credits, students and hours taught so far. */
    @Transactional(readOnly = true)
    public WorkloadView workload(Long semesterId) {
        if (!access.has(Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.HEAD_OF_DEPARTMENT,
                Role.MANAGEMENT_VIEWER)) {
            throw new AccessDeniedException("Not allowed to see teaching workload");
        }
        List<ModuleOffering> visible = offerings.findBySemesterIdOrderByIdAsc(semesterId).stream().filter(this::oversees).toList();
        Map<Long, Object[]> totals = totals(semesterId);
        Map<Long, WorkloadModule> modules = new LinkedHashMap<>();
        for (ModuleOffering o : visible) {
            Object[] tot = totals.get(o.getId());
            modules.put(o.getId(), new WorkloadModule(o.getId(), o.getModule().getCode(), o.getModule().getTitle(), o.getModule().getCredits(),
                    registrations.students(semesterId, o.getModule().getId(), APPROVED).size(), tot == null ? 0 : ((Number) tot[1]).intValue(),
                    tot == null ? 0 : ((Number) tot[2]).intValue(), o.getStaff().size()));
        }
        Map<Long, List<WorkloadModule>> byStaff = new HashMap<>();
        for (ModuleOffering o : visible) {
            for (OfferingStaff s : o.getStaff()) byStaff.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(modules.get(o.getId()));
        }
        Map<Long, UserAccount> people = users.findAllById(byStaff.keySet()).stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        List<Workload> staff = byStaff.entrySet().stream().map(e -> {
            UserAccount u = people.get(e.getKey());
            List<WorkloadModule> mods = e.getValue().stream().sorted(Comparator.comparing(WorkloadModule::code)).toList();
            int minutes = mods.stream().mapToInt(WorkloadModule::minutesTaught).sum();
            return new Workload(e.getKey(), u == null ? "—" : u.getFullName(),
                    u == null ? List.of() : u.getRoles().stream().map(r -> r.getRole().name()).distinct().sorted().toList(), mods.size(),
                    mods.stream().map(WorkloadModule::credits).reduce(BigDecimal.ZERO, BigDecimal::add),
                    mods.stream().mapToInt(WorkloadModule::students).sum(), mods.stream().mapToInt(WorkloadModule::sessions).sum(),
                    BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP), mods);
        }).sorted(Comparator.comparing(Workload::fullName)).toList();
        List<WorkloadModule> unallocated = visible.stream().filter(o -> o.getStaff().isEmpty()).map(o -> modules.get(o.getId()))
                .sorted(Comparator.comparing(WorkloadModule::code)).toList();
        return new WorkloadView(semesterId, staff, unallocated);
    }

    // ------------------------------------------------------------------ student portal

    @Transactional(readOnly = true)
    public MyAttendance mine(Long semesterId) {
        Student me = students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        List<SemesterRegistration> regs = semesterRegistrations.findByStudentIdOrderBySubmittedAtDesc(me.getId()).stream()
                .filter(r -> r.getStatus() == APPROVED).toList();
        SemesterRegistration reg = semesterId == null
                ? regs.stream().max(Comparator.comparing((SemesterRegistration r) -> r.getSemester().getStartDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(r -> r.getSemester().getId())).orElse(null)
                : regs.stream().filter(r -> r.getSemester().getId().equals(semesterId)).findFirst().orElse(null);
        BigDecimal threshold = regulations.settings().getMinAttendancePercent();
        if (reg == null) return new MyAttendance(null, null, threshold, false, List.of());
        Semester sem = reg.getSemester();
        Map<Long, Tally> tallies = Tally.byRegistration(records.findByStudentAndSemester(me.getId(), sem.getId()));
        Map<Long, ExamEligibility> elig = eligibility.findByStudentId(me.getId()).stream()
                .collect(Collectors.toMap(ExamEligibility::getModuleRegistrationId, Function.identity()));
        Map<Long, ModuleOffering> bySemModule = offerings.findBySemesterIdOrderByIdAsc(sem.getId()).stream()
                .collect(Collectors.toMap(o -> o.getModule().getId(), Function.identity()));
        List<MyModuleAttendance> mods = reg.getModules().stream().sorted(Comparator.comparing(m -> m.getModule().getCode())).map(m -> {
            Tally t = tallies.getOrDefault(m.getId(), Tally.NONE);
            ExamEligibility e = elig.get(m.getId());
            ModuleOffering o = bySemModule.get(m.getModule().getId());
            return new MyModuleAttendance(o == null ? null : o.getId(), m.getModule().getCode(), m.getModule().getTitle(), t.counted(), t.attended(),
                    t.absent(), t.excused(), t.percent(), t.below(threshold), e == null ? null : e.isEligible(), e != null && e.isExempted(),
                    e == null || e.isEligible() ? List.of() : e.getDetail());
        }).toList();
        return new MyAttendance(sem.getId(), sem.getName(), threshold, mods.stream().anyMatch(m -> m.eligible() != null), mods);
    }

    // ------------------------------------------------------------------ access

    boolean isMine(ModuleOffering o, Long me) {
        return o.getStaff().stream().anyMatch(s -> s.getUserId().equals(me));
    }

    /** Whether the signed-in person oversees a module: the Examinations Office, academic management, or its Head of Department. */
    boolean oversees(ModuleOffering o) {
        return access.follows(o.getModule());
    }

    private void requireRead(ModuleOffering o) {
        if (!isMine(o, access.me()) && !oversees(o)) throw new AccessDeniedException("Not your module");
    }

    private boolean canRecord(ModuleOffering o) {
        return isMine(o, access.me()) && o.getStatus() == ModuleOffering.Status.DRAFT;
    }

    void requireRecorder(ModuleOffering o) {
        if (!isMine(o, access.me())) throw new AccessDeniedException("Only the module's lecturers record its attendance");
        if (o.getStatus() != ModuleOffering.Status.DRAFT) {
            throw new BusinessRuleException("RESULTS_SUBMITTED", "This module's results have been submitted, so its attendance can no longer change");
        }
    }

    // ------------------------------------------------------------------ helpers

    ModuleOffering offering(Long id) {
        return offerings.findById(id).orElseThrow(() -> new NotFoundException("Module offering", id));
    }

    ClassSession require(Long id) {
        return sessions.findById(id).orElseThrow(() -> new NotFoundException("Class session", id));
    }

    List<ModuleRegistration> classList(ModuleOffering o) {
        return registrations.students(o.getSemester().getId(), o.getModule().getId(), APPROVED);
    }

    private Map<Long, Object[]> totals(Long semesterId) {
        return sessions.totalsBySemester(semesterId).stream().collect(Collectors.toMap(r -> (Long) r[0], r -> r));
    }

    SessionView sessionView(ClassSession cs) {
        List<ModuleRegistration> regs = classList(cs.getOffering());
        List<RegisterLine> lines = registerOf(cs, regs);
        return new SessionView(cs.getId(), cs.getOffering().getId(), cs.getSessionDate(), cs.getStartTime(), cs.getDurationMinutes(), cs.getKind().name(),
                cs.getTopic(), cs.getRecordedByName(), cs.getCreatedAt(), canRecord(cs.getOffering()) && !cs.checkInOpen(), checkInState(cs, regs.size(), lines),
                lines);
    }

    /** The session's register over the current class list, with self check-ins and phones used for more than one student flagged. */
    List<RegisterLine> registerOf(ClassSession cs, List<ModuleRegistration> regs) {
        Map<Long, AttendanceRecord> byReg = cs.getRecords().stream().collect(Collectors.toMap(AttendanceRecord::getModuleRegistrationId, Function.identity()));
        Map<String, Long> perDevice = cs.getRecords().stream().filter(r -> r.getDeviceHash() != null)
                .collect(Collectors.groupingBy(AttendanceRecord::getDeviceHash, Collectors.counting()));
        return new ArrayList<>(regs.stream().map(m -> {
            AttendanceRecord r = byReg.get(m.getId());
            if (r == null) return line(m, null, null);
            Student s = m.getRegistration().getStudent();
            return new RegisterLine(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), r.getStatus().name(), r.getNote(),
                    r.getSource().name(), r.getCheckedInAt(), r.getDeviceHash() != null && perDevice.getOrDefault(r.getDeviceHash(), 0L) > 1);
        }).toList());
    }

    static TeachingDtos.CheckInState checkInState(ClassSession cs, int classSize, List<RegisterLine> lines) {
        if (cs.getCheckinOpenedAt() == null) return null;
        return new TeachingDtos.CheckInState(cs.checkInOpen(), cs.getCheckinOpenedAt(), cs.getCheckinClosesAt(),
                cs.getCheckinLateAfter() == null ? 0 : cs.getCheckinLateAfter(), cs.getCheckinClosedAt(),
                (int) lines.stream().filter(l -> l.checkedInAt() != null).count(), classSize, (int) lines.stream().filter(RegisterLine::sharedDevice).count());
    }

    private static RegisterLine line(ModuleRegistration m, String status, String note) {
        Student s = m.getRegistration().getStudent();
        return new RegisterLine(m.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), status, note, null, null, false);
    }

    private static SessionSummary summary(ClassSession cs, Map<AttendanceRecord.Status, Integer> counts, int selfCheckIns) {
        return new SessionSummary(cs.getId(), cs.getSessionDate(), cs.getStartTime(), cs.getDurationMinutes(), cs.getKind().name(), cs.getTopic(),
                counts.getOrDefault(AttendanceRecord.Status.PRESENT, 0), counts.getOrDefault(AttendanceRecord.Status.LATE, 0),
                counts.getOrDefault(AttendanceRecord.Status.ABSENT, 0), counts.getOrDefault(AttendanceRecord.Status.EXCUSED, 0),
                cs.getRecordedByName(), cs.getCreatedAt(), selfCheckIns, cs.checkInOpen());
    }

    private Map<Long, String> names(Collection<ModuleOffering> os) {
        Set<Long> ids = os.stream().flatMap(o -> o.getStaff().stream().map(OfferingStaff::getUserId)).collect(Collectors.toSet());
        return users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    private static List<String> lecturers(ModuleOffering o, Map<Long, String> names) {
        return o.getStaff().stream().map(s -> names.getOrDefault(s.getUserId(), "—")).sorted().toList();
    }

    private List<String> lecturerNames(ModuleOffering o) {
        return lecturers(o, names(List.of(o)));
    }

    static String pct(BigDecimal p) {
        return p == null ? "—" : p.stripTrailingZeros().toPlainString() + "%";
    }
}
