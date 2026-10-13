package tz.co.msingi.cims.teaching;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.document.QrCodes;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.teaching.TeachingDtos.CheckInDone;
import tz.co.msingi.cims.teaching.TeachingDtos.CheckInRequest;
import tz.co.msingi.cims.teaching.TeachingDtos.CheckInScreen;
import tz.co.msingi.cims.teaching.TeachingDtos.RegisterLine;
import tz.co.msingi.cims.teaching.TeachingDtos.SessionView;
import tz.co.msingi.cims.teaching.TeachingDtos.StudentCheckIn;
import tz.co.msingi.cims.teaching.TeachingRepositories.AttendanceRecordRepository;
import tz.co.msingi.cims.teaching.TeachingRepositories.ClassSessionRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Self check-in (FR-TCH-03, optional in the SRS; BP-07 step 2). The lecturer opens check-in for a class in progress and shows the
 * rotating code (and its QR code) on the projector; students in the class check in from the student portal. Checking in after the
 * late limit counts as late. When check-in closes (by the lecturer, or on time by {@link CheckInJob}), everyone in the class who did
 * not check in is recorded absent, low-attendance warnings go out, and the lecturer can correct the register as for any session.
 * A phone that checks in more than one student for a session is flagged to the lecturer; wrong codes are limited per student.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    static final int MAX_FAILED = 5;
    static final int FAILED_WINDOW_MINUTES = 10;
    private static final Pattern DIGITS = Pattern.compile("\\d{6}");
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.:\\-\\[\\]]{1,200}$");

    private final AttendanceService attendance;
    private final ClassSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final StudentRepository students;
    private final Attempts attempts;
    private final AuditService audit;

    // ------------------------------------------------------------------ lecturer

    @Transactional
    public SessionView open(Long offeringId, CheckInRequest req) {
        ModuleOffering o = attendance.offering(offeringId);
        attendance.requireRecorder(o);
        sessions.findOpenCheckIns().stream().filter(cs -> cs.getOffering().getId().equals(o.getId())).findFirst().ifPresent(cs -> {
            throw new BusinessRuleException("CHECKIN_ALREADY_OPEN", "Self check-in is already open for " + o.getModule().getCode()
                    + " (session at " + cs.getStartTime() + "). Close it before opening another.");
        });
        OffsetDateTime now = OffsetDateTime.now(AttendanceService.ZONE).truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = now.toLocalDate();
        AttendanceService.checkDate(o.getSemester(), today);
        var start = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        if (sessions.existsByOfferingIdAndSessionDateAndStartTime(o.getId(), today, start)) {
            throw new BusinessRuleException("SESSION_EXISTS", "A session of " + o.getModule().getCode() + " is already recorded at " + start + " today");
        }
        ClassSession cs = new ClassSession();
        cs.setOffering(o);
        cs.setClientRef(UUID.randomUUID());
        cs.setRecordedById(CurrentUser.requireId());
        cs.setRecordedByName(CurrentUser.name());
        cs.setSessionDate(today);
        cs.setStartTime(start);
        cs.setDurationMinutes(req.durationMinutes().shortValue());
        cs.setKind(req.kind());
        cs.setTopic(req.topic() == null || req.topic().isBlank() ? null : req.topic().trim());
        cs.setCheckinSecret(CheckInCodes.newSecret());
        cs.setCheckinOpenedAt(now);
        cs.setCheckinClosesAt(now.plusMinutes(req.openMinutes()));
        cs.setCheckinLateAfter(req.lateAfterMinutes().shortValue());
        sessions.saveAndFlush(cs);
        audit.record("CHECKIN_OPENED", "ClassSession", cs.getId(), Map.of("module", o.getModule().getCode(), "minutes", req.openMinutes(),
                "lateAfter", req.lateAfterMinutes()));
        return attendance.sessionView(cs);
    }

    /**
     * The lecturer's check-in screen: the code of the current step, a QR code of the check-in link on the institution's own address
     * ({@code base}, the page's origin), and who has checked in. Check-in past its closing time is closed here.
     */
    @Transactional
    public CheckInScreen screen(Long sessionId, String base) {
        ClassSession cs = attendance.require(sessionId);
        ModuleOffering o = cs.getOffering();
        if (!attendance.isMine(o, CurrentUser.requireId())) throw new AccessDeniedException("Only the module's lecturers see its check-in code");
        Instant now = Instant.now();
        if (cs.checkInOpen() && !cs.getCheckinClosesAt().toInstant().isAfter(now)) finish(cs, "on time");
        List<ModuleRegistration> regs = attendance.classList(o);
        List<RegisterLine> lines = attendance.registerOf(cs, regs);
        List<RegisterLine> recent = lines.stream().filter(l -> l.checkedInAt() != null)
                .sorted(Comparator.comparing(RegisterLine::checkedInAt).reversed()).limit(8).toList();
        String code = null, url = null, qr = null;
        if (cs.checkInOpen()) {
            code = CheckInCodes.code(cs.getCheckinSecret(), CheckInCodes.step(now));
            if (base != null && ORIGIN.matcher(base).matches()) {
                url = base + "/student/check-in?code=" + code;
                qr = "data:image/png;base64," + Base64.getEncoder().encodeToString(QrCodes.png(url, 360));
            }
        }
        return new CheckInScreen(cs.getId(), o.getModule().getCode(), o.getModule().getTitle(), code, CheckInCodes.secondsLeft(now),
                CheckInCodes.STEP_SECONDS, url, qr, AttendanceService.checkInState(cs, regs.size(), lines), recent);
    }

    @Transactional
    public SessionView close(Long sessionId) {
        ClassSession cs = attendance.require(sessionId);
        if (!attendance.isMine(cs.getOffering(), CurrentUser.requireId())) {
            throw new AccessDeniedException("Only the module's lecturers record its attendance");
        }
        if (cs.checkInOpen()) finish(cs, "by the lecturer");
        return attendance.sessionView(cs);
    }

    /** Closes check-in sessions past their closing time; returns how many. Run by {@link CheckInJob} for each institution. */
    @Transactional
    public int closeExpired() {
        Instant now = Instant.now();
        List<ClassSession> due = sessions.findOpenCheckIns().stream().filter(cs -> !cs.getCheckinClosesAt().toInstant().isAfter(now)).toList();
        due.forEach(cs -> finish(cs, "on time"));
        return due.size();
    }

    /** Ends check-in: everyone in the class without a record is absent; the code stops working. */
    private void finish(ClassSession cs, String how) {
        ModuleOffering o = cs.getOffering();
        Set<Long> recorded = cs.getRecords().stream().map(AttendanceRecord::getModuleRegistrationId).collect(Collectors.toSet());
        List<AttendanceRecord> absent = new ArrayList<>();
        if (o.getStatus() == ModuleOffering.Status.DRAFT) {
            for (ModuleRegistration m : attendance.classList(o)) {
                if (recorded.contains(m.getId())) continue;
                AttendanceRecord r = new AttendanceRecord();
                r.setSession(cs);
                r.setModuleRegistrationId(m.getId());
                r.setStudentId(m.getStudentId());
                r.setStatus(AttendanceRecord.Status.ABSENT);
                cs.getRecords().add(r);
                absent.add(r);
            }
        }
        cs.setCheckinClosedAt(OffsetDateTime.now(AttendanceService.ZONE));
        cs.setCheckinSecret(null);
        sessions.saveAndFlush(cs);
        long self = cs.getRecords().stream().filter(r -> r.getCheckedInAt() != null).count();
        audit.record("CHECKIN_CLOSED", "ClassSession", cs.getId(), Map.of("module", o.getModule().getCode(), "checkedIn", self,
                "absent", absent.size(), "closed", how));
        if (o.getStatus() == ModuleOffering.Status.DRAFT) attendance.warnLowAttendance(o);
    }

    // ------------------------------------------------------------------ student

    @Transactional
    public CheckInDone checkIn(StudentCheckIn req) {
        Student me = students.findByUserId(CurrentUser.requireId())
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        if (attempts.failedRecently(me.getId()) >= MAX_FAILED) {
            throw new TooManyRequestsException("Too many wrong codes. Wait " + FAILED_WINDOW_MINUTES + " minutes, then try again with the code on the screen.");
        }
        String code = req.code().trim();
        Instant now = Instant.now();
        List<ClassSession> matching = DIGITS.matcher(code).matches()
                ? sessions.findOpenCheckIns().stream().filter(cs -> cs.getCheckinClosesAt().toInstant().isAfter(now))
                .filter(cs -> cs.getOffering().getStatus() == ModuleOffering.Status.DRAFT)
                .filter(cs -> CheckInCodes.matches(cs.getCheckinSecret(), code, now)).toList()
                : List.of();
        for (ClassSession cs : matching) {
            ModuleRegistration m = attendance.classList(cs.getOffering()).stream().filter(r -> Objects.equals(r.getStudentId(), me.getId())).findFirst().orElse(null);
            if (m == null) continue;
            ModuleOffering o = cs.getOffering();
            AttendanceRecord existing = cs.getRecords().stream().filter(r -> r.getModuleRegistrationId().equals(m.getId())).findFirst().orElse(null);
            if (existing != null) {
                return new CheckInDone(cs.getId(), o.getModule().getCode(), o.getModule().getTitle(), existing.getStatus().name(),
                        existing.getCheckedInAt(), true);
            }
            OffsetDateTime at = OffsetDateTime.now(AttendanceService.ZONE);
            int late = cs.getCheckinLateAfter() == null ? 0 : cs.getCheckinLateAfter();
            AttendanceRecord r = new AttendanceRecord();
            r.setSession(cs);
            r.setModuleRegistrationId(m.getId());
            r.setStudentId(me.getId());
            r.setStatus(at.isAfter(cs.getCheckinOpenedAt().plusMinutes(late)) ? AttendanceRecord.Status.LATE : AttendanceRecord.Status.PRESENT);
            r.setSource(AttendanceRecord.Source.SELF);
            r.setCheckedInAt(at);
            if (req.deviceId() != null && !req.deviceId().isBlank()) {
                r.setDeviceHash(CheckInCodes.sha256(TenantContext.requireTenantId() + ":" + req.deviceId().trim()));
            }
            records.save(r);
            attempts.record(me.getId(), cs.getId(), true);
            return new CheckInDone(cs.getId(), o.getModule().getCode(), o.getModule().getTitle(), r.getStatus().name(), at, false);
        }
        attempts.record(me.getId(), matching.isEmpty() ? null : matching.get(0).getId(), false);
        if (!matching.isEmpty()) {
            throw new BusinessRuleException("CHECKIN_NOT_IN_CLASS", "That code is for " + matching.get(0).getOffering().getModule().getCode()
                    + ", which you are not registered for this semester.");
        }
        throw new BusinessRuleException("CHECKIN_CODE_INVALID", "That code is not valid now. Type the code on the lecturer's screen; it changes every "
                + CheckInCodes.STEP_SECONDS + " seconds.");
    }

    /** Check-in attempts, recorded in their own transaction so a refused attempt still counts towards the limit. */
    @Component
    @RequiredArgsConstructor
    public static class Attempts {

        private final JdbcTemplate jdbc;

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void record(Long studentId, Long sessionId, boolean ok) {
            jdbc.update("insert into checkin_attempts (tenant_id, student_id, session_id, succeeded) values (cims_current_tenant(), ?, ?, ?)",
                    studentId, sessionId, ok);
        }

        @Transactional(readOnly = true)
        public int failedRecently(Long studentId) {
            Integer n = jdbc.queryForObject("select count(*) from checkin_attempts where student_id = ? and not succeeded and attempted_at > now() - make_interval(mins => ?)",
                    Integer.class, studentId, FAILED_WINDOW_MINUTES);
            return n == null ? 0 : n;
        }
    }
}
