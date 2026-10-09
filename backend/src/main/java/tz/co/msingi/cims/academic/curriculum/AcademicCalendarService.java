package tz.co.msingi.cims.academic.curriculum;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.CalendarView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.EventRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.EventView;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.SemesterRequest;
import tz.co.msingi.cims.academic.curriculum.CurriculumDtos.SemesterView;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CalendarEventRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Semesters with their registration periods, and the key dates of the academic calendar (FR-ACA-04). */
@Service
@RequiredArgsConstructor
public class AcademicCalendarService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final SemesterRepository semesters;
    private final CalendarEventRepository events;
    private final AcademicYearRepository years;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public CalendarView calendar(Long academicYearId) {
        AcademicYear y = year(academicYearId);
        LocalDate today = LocalDate.now(ZONE);
        return new CalendarView(y.getId(), y.getCode(),
                semesters.findByAcademicYearIdOrderBySemesterNoAsc(y.getId()).stream().map(s -> SemesterView.of(s, today)).toList(),
                events.findByAcademicYearIdOrderByStartDateAscIdAsc(y.getId()).stream().map(EventView::of).toList());
    }

    @Transactional(readOnly = true)
    public List<SemesterView> allSemesters() {
        LocalDate today = LocalDate.now(ZONE);
        return semesters.findAllByOrderByStartDateDesc().stream().map(s -> SemesterView.of(s, today)).toList();
    }

    @Transactional
    public SemesterView createSemester(SemesterRequest req) {
        AcademicYear y = year(req.academicYearId());
        if (semesters.existsByAcademicYearIdAndSemesterNo(y.getId(), req.semesterNo())) {
            throw new BusinessRuleException("DUPLICATE_SEMESTER", "Semester " + req.semesterNo() + " of " + y.getCode() + " already exists");
        }
        Semester s = new Semester();
        s.setAcademicYear(y);
        apply(s, req, y);
        semesters.save(s);
        audit.record("SEMESTER_CREATED", "Semester", s.getId(), Map.of("year", y.getCode(), "semester", s.getSemesterNo()));
        return SemesterView.of(s, LocalDate.now(ZONE));
    }

    @Transactional
    public SemesterView updateSemester(Long id, SemesterRequest req) {
        Semester s = semesters.findById(id).orElseThrow(() -> new NotFoundException("Semester", id));
        if (!Objects.equals(s.getAcademicYear().getId(), req.academicYearId()) || s.getSemesterNo() != req.semesterNo()) {
            throw new BusinessRuleException("CANNOT_MOVE_SEMESTER", "A semester's year and number cannot change; create a new semester instead");
        }
        apply(s, req, s.getAcademicYear());
        audit.record("SEMESTER_UPDATED", "Semester", s.getId(), Map.of("registrationCloses", s.getRegistrationCloses().toString()));
        return SemesterView.of(s, LocalDate.now(ZONE));
    }

    private static void apply(Semester s, SemesterRequest req, AcademicYear y) {
        if (!req.endDate().isAfter(req.startDate())) throw new BusinessRuleException("INVALID_DATES", "The semester must end after it starts");
        if (req.startDate().isBefore(y.getStartDate()) || req.endDate().isAfter(y.getEndDate())) {
            throw new BusinessRuleException("INVALID_DATES", "The semester must fall within " + y.getCode() + " (" + y.getStartDate() + " to " + y.getEndDate() + ")");
        }
        if (req.registrationCloses().isBefore(req.registrationOpens())) {
            throw new BusinessRuleException("INVALID_DATES", "Registration must close after it opens");
        }
        if (req.marksEntryOpens() != null && req.marksEntryCloses() != null && req.marksEntryCloses().isBefore(req.marksEntryOpens())) {
            throw new BusinessRuleException("INVALID_DATES", "Marks entry must close after it opens");
        }
        if (req.lateRegistrationCloses() != null && req.lateRegistrationCloses().isBefore(req.registrationCloses())) {
            throw new BusinessRuleException("INVALID_DATES", "Late registration must end after normal registration closes");
        }
        s.setSemesterNo(req.semesterNo());
        s.setName(req.name().trim());
        s.setStartDate(req.startDate());
        s.setEndDate(req.endDate());
        s.setRegistrationOpens(req.registrationOpens());
        s.setRegistrationCloses(req.registrationCloses());
        s.setLateRegistrationCloses(req.lateRegistrationCloses());
        s.setMarksEntryOpens(req.marksEntryOpens());
        s.setMarksEntryCloses(req.marksEntryCloses());
    }

    @Transactional
    public EventView createEvent(EventRequest req) {
        CalendarEvent e = new CalendarEvent();
        applyEvent(e, req);
        events.save(e);
        audit.record("CALENDAR_EVENT_CREATED", "CalendarEvent", e.getId(), Map.of("title", e.getTitle(), "start", e.getStartDate().toString()));
        return EventView.of(e);
    }

    @Transactional
    public EventView updateEvent(Long id, EventRequest req) {
        CalendarEvent e = events.findById(id).orElseThrow(() -> new NotFoundException("Calendar event", id));
        applyEvent(e, req);
        audit.record("CALENDAR_EVENT_UPDATED", "CalendarEvent", e.getId(), Map.of("title", e.getTitle()));
        return EventView.of(e);
    }

    @Transactional
    public void deleteEvent(Long id) {
        CalendarEvent e = events.findById(id).orElseThrow(() -> new NotFoundException("Calendar event", id));
        events.delete(e);
        audit.record("CALENDAR_EVENT_DELETED", "CalendarEvent", id, Map.of("title", e.getTitle()));
    }

    private void applyEvent(CalendarEvent e, EventRequest req) {
        AcademicYear y = year(req.academicYearId());
        if (req.endDate().isBefore(req.startDate())) throw new BusinessRuleException("INVALID_DATES", "The event must end on or after its start");
        if (req.startDate().isBefore(y.getStartDate()) || req.endDate().isAfter(y.getEndDate())) {
            throw new BusinessRuleException("INVALID_DATES", "The dates must fall within " + y.getCode() + " (" + y.getStartDate() + " to " + y.getEndDate() + ")");
        }
        Semester s = req.semesterId() == null ? null : semesters.findById(req.semesterId()).orElseThrow(() -> new NotFoundException("Semester", req.semesterId()));
        if (s != null && !s.getAcademicYear().getId().equals(y.getId())) {
            throw new BusinessRuleException("SEMESTER_OF_OTHER_YEAR", "The semester belongs to another academic year");
        }
        e.setAcademicYear(y);
        e.setSemester(s);
        e.setKind(req.kind());
        e.setTitle(req.title().trim());
        e.setStartDate(req.startDate());
        e.setEndDate(req.endDate());
    }

    private AcademicYear year(Long id) {
        return years.findById(id).orElseThrow(() -> new NotFoundException("Academic year", id));
    }
}
