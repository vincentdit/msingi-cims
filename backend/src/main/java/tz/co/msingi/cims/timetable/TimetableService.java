package tz.co.msingi.cims.timetable;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.campus.CampusRepositories.CampusRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.teaching.ClassSession;
import tz.co.msingi.cims.timetable.Clashes.ClassItem;
import tz.co.msingi.cims.timetable.TimetableData.Offering;
import tz.co.msingi.cims.timetable.TimetableDtos.CopyResult;
import tz.co.msingi.cims.timetable.TimetableDtos.Kind;
import tz.co.msingi.cims.timetable.TimetableDtos.OfferingRow;
import tz.co.msingi.cims.timetable.TimetableDtos.PublishResult;
import tz.co.msingi.cims.timetable.TimetableDtos.SemesterTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotView;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueView;
import tz.co.msingi.cims.timetable.TimetableRepositories.ExamSittingRepository;
import tz.co.msingi.cims.timetable.TimetableRepositories.TeachingVenueRepository;
import tz.co.msingi.cims.timetable.TimetableRepositories.TimetableSlotRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The weekly class timetable of a semester (FR-TCH-05): teaching venues, classes per module offering with their clash checks, copying
 * an earlier semester's classes, and publishing to students (with an optional SMS).
 * Academic management keeps the whole timetable; a Head of Department keeps the classes of their department's modules.
 */
@Service
@RequiredArgsConstructor
public class TimetableService {

    static final Role[] ACADEMIC = {Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC};
    static final Role[] EXAMS = {Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER};
    static final Role[] VENUE_KEEPERS = {Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.EXAMINATIONS_OFFICER};

    private final TeachingVenueRepository venues;
    private final TimetableSlotRepository slots;
    private final ExamSittingRepository sittings;
    private final ModuleOfferingRepository offerings;
    private final SemesterRepository semesters;
    private final CampusRepository campuses;
    private final TimetableData data;
    private final ExamAccess access;
    private final AuditService audit;
    private final Messaging messaging;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ venues

    @Transactional(readOnly = true)
    public List<VenueView> venues() {
        Map<Long, String> campusNames = new HashMap<>();
        campuses.findAll().forEach(c -> campusNames.put(c.getId(), c.getName()));
        return venues.findAllByOrderByCodeAsc().stream().map(v -> view(v, campusNames)).toList();
    }

    @Transactional
    public VenueView createVenue(VenueRequest req) {
        access.require("keep teaching venues", VENUE_KEEPERS);
        venues.findByCodeIgnoreCase(req.code().trim()).ifPresent(v -> {
            throw new BusinessRuleException("VENUE_CODE_TAKEN", "Another venue already has the code " + v.getCode() + ".");
        });
        TeachingVenue v = new TeachingVenue();
        apply(v, req);
        venues.save(v);
        audit.record("VENUE_CREATED", "TeachingVenue", v.getId(), Map.of("code", v.getCode(), "capacity", v.getCapacity()));
        return single(v);
    }

    @Transactional
    public VenueView updateVenue(Long id, VenueRequest req) {
        access.require("keep teaching venues", VENUE_KEEPERS);
        TeachingVenue v = venues.findById(id).orElseThrow(() -> new NotFoundException("Venue", id));
        venues.findByCodeIgnoreCase(req.code().trim()).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
            throw new BusinessRuleException("VENUE_CODE_TAKEN", "Another venue already has the code " + o.getCode() + ".");
        });
        apply(v, req);
        audit.record("VENUE_UPDATED", "TeachingVenue", v.getId(), Map.of("code", v.getCode(), "capacity", v.getCapacity(), "active", v.isActive()));
        return single(v);
    }

    private void apply(TeachingVenue v, VenueRequest req) {
        if (req.campusId() != null && !campuses.existsById(req.campusId())) throw new NotFoundException("Campus", req.campusId());
        v.setCode(req.code().trim().toUpperCase());
        v.setName(req.name().trim());
        v.setKind(req.kind());
        v.setCapacity(req.capacity());
        v.setExamCapacity(req.examCapacity());
        v.setCampusId(req.campusId());
        if (req.active() != null) v.setActive(req.active());
    }

    private VenueView single(TeachingVenue v) {
        Map<Long, String> names = new HashMap<>();
        if (v.getCampusId() != null) campuses.findById(v.getCampusId()).ifPresent(c -> names.put(c.getId(), c.getName()));
        return view(v, names);
    }

    static VenueView view(TeachingVenue v, Map<Long, String> campusNames) {
        return new VenueView(v.getId(), v.getCode(), v.getName(), v.getKind().name(), v.getCapacity(), v.getExamCapacity(), v.seatsForExams(),
                v.getCampusId(), v.getCampusId() == null ? null : campusNames.get(v.getCampusId()), v.isActive());
    }

    // ------------------------------------------------------------------ the semester's class timetable

    @Transactional(readOnly = true)
    public SemesterTimetable semester(Long semesterId) {
        TimetableData.Semester sem = data.semester(semesterId);
        Map<Long, Offering> offs = data.offerings(semesterId);
        List<TimetableSlot> all = slots.findBySemester(semesterId);
        Map<Long, ModuleOffering> entities = new HashMap<>();
        offerings.findBySemesterIdOrderByIdAsc(semesterId).forEach(o -> entities.put(o.getId(), o));
        Map<Long, String> names = names(all, offs);
        List<SlotView> views = all.stream().map(s -> view(s, offs, names, sem.classPublishedAt())).toList();
        Map<Long, int[]> perOffering = new HashMap<>();
        for (TimetableSlot s : all) {
            int[] t = perOffering.computeIfAbsent(s.getOffering().getId(), k -> new int[2]);
            t[0] += (int) Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
            t[1]++;
        }
        boolean academic = access.has(ACADEMIC);
        List<OfferingRow> rows = offs.values().stream().map(o -> {
            int[] t = perOffering.getOrDefault(o.id(), new int[2]);
            ModuleOffering e = entities.get(o.id());
            boolean edit = academic || (e != null && access.moderates(e.getModule()));
            return new OfferingRow(o.id(), o.code(), o.title(), o.departmentName(), o.lecturers(), o.students().size(), o.cohorts(), t[0], t[1], edit);
        }).toList();
        return new SemesterTimetable(sem.id(), sem.label(), sem.startDate(), sem.endDate(), sem.classPublishedAt(), sem.classPublishedBy(),
                rows.stream().anyMatch(OfferingRow::canEdit), academic, rows, views, venues().stream().filter(VenueView::active).toList(),
                Clashes.classWarnings(items(all), offs));
    }

    @Transactional
    public SlotView createSlot(Long semesterId, SlotRequest req) {
        ModuleOffering o = offering(req.offeringId());
        if (!o.getSemester().getId().equals(semesterId)) throw new BusinessRuleException("OFFERING_NOT_IN_SEMESTER", "That module is not offered in this semester.");
        requireEdit(o);
        TimetableSlot s = new TimetableSlot();
        s.setOffering(o);
        apply(s, req);
        check(s);
        slots.save(s);
        audit.record("TIMETABLE_SLOT_ADDED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "when", item(s).when(),
                "venue", s.getVenue().getCode()));
        return single(s);
    }

    @Transactional
    public SlotView updateSlot(Long id, SlotRequest req) {
        TimetableSlot s = slots.findById(id).orElseThrow(() -> new NotFoundException("Class", id));
        requireEdit(s.getOffering());
        if (!req.offeringId().equals(s.getOffering().getId())) {
            ModuleOffering o = offering(req.offeringId());
            if (!o.getSemester().getId().equals(s.getOffering().getSemester().getId())) {
                throw new BusinessRuleException("OFFERING_NOT_IN_SEMESTER", "That module is not offered in this semester.");
            }
            requireEdit(o);
            s.setOffering(o);
        }
        String before = item(s).when() + " " + s.getVenue().getCode();
        apply(s, req);
        check(s);
        audit.record("TIMETABLE_SLOT_CHANGED", "ModuleOffering", s.getOffering().getId(), Map.of("module", s.getOffering().getModule().getCode(),
                "from", before, "to", item(s).when() + " " + s.getVenue().getCode()));
        return single(s);
    }

    @Transactional
    public void deleteSlot(Long id) {
        TimetableSlot s = slots.findById(id).orElseThrow(() -> new NotFoundException("Class", id));
        requireEdit(s.getOffering());
        audit.record("TIMETABLE_SLOT_REMOVED", "ModuleOffering", s.getOffering().getId(), Map.of("module", s.getOffering().getModule().getCode(),
                "when", item(s).when()));
        slots.delete(s);
    }

    private void apply(TimetableSlot s, SlotRequest req) {
        ClassSession.Kind kind;
        try {
            kind = ClassSession.Kind.valueOf(req.kind().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_KIND", "The class type must be lecture, tutorial, practical or seminar.");
        }
        if (!req.endTime().isAfter(req.startTime())) throw new BusinessRuleException("INVALID_TIMES", "The class must end after it starts.");
        TeachingVenue v = venues.findById(req.venueId()).orElseThrow(() -> new NotFoundException("Venue", req.venueId()));
        if (!v.isActive()) throw new BusinessRuleException("VENUE_INACTIVE", v.getCode() + " is not in use.");
        if (req.lecturerId() != null && s.getOffering().getStaff().stream().noneMatch(st -> st.getUserId().equals(req.lecturerId()))) {
            throw new BusinessRuleException("LECTURER_NOT_ALLOCATED", "The lecturer is not allocated to " + s.getOffering().getModule().getCode()
                    + ". The Head of Department allocates lecturers on Teaching.");
        }
        s.setKind(kind);
        s.setDayOfWeek(req.dayOfWeek().shortValue());
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        s.setVenue(v);
        s.setLecturerUserId(req.lecturerId());
        s.setGroupLabel(blank(req.groupLabel()));
        s.setNote(blank(req.note()));
        s.setChangedAt(OffsetDateTime.now());
    }

    private void check(TimetableSlot s) {
        Long semesterId = s.getOffering().getSemester().getId();
        List<TimetableSlot> all = slots.findBySemester(semesterId);
        Map<Long, Offering> offs = data.offerings(semesterId);
        Set<Long> people = new HashSet<>();
        offs.values().forEach(o -> people.addAll(o.lecturerIds()));
        Clashes.checkClass(item(s), items(all), offs, data.names(people));
    }

    // ------------------------------------------------------------------ copying an earlier semester's classes

    @Transactional
    public CopyResult copy(Long semesterId, Long fromSemesterId) {
        access.require("copy the class timetable", ACADEMIC);
        if (semesterId.equals(fromSemesterId)) throw new BusinessRuleException("SAME_SEMESTER", "Choose an earlier semester to copy from.");
        data.semester(fromSemesterId);
        Map<Long, ModuleOffering> byModule = new HashMap<>();
        offerings.findBySemesterIdOrderByIdAsc(semesterId).forEach(o -> byModule.put(o.getModule().getId(), o));
        Set<Long> already = new HashSet<>();
        List<TimetableSlot> existing = new ArrayList<>(slots.findBySemester(semesterId));
        existing.forEach(s -> already.add(s.getOffering().getId()));
        Map<Long, Offering> offs = data.offerings(semesterId);
        Set<Long> people = new HashSet<>();
        offs.values().forEach(o -> people.addAll(o.lecturerIds()));
        Map<Long, String> names = data.names(people);
        int copied = 0, skipped = 0;
        List<String> notes = new ArrayList<>();
        for (TimetableSlot old : slots.findBySemester(fromSemesterId)) {
            ModuleOffering target = byModule.get(old.getOffering().getModule().getId());
            String code = old.getOffering().getModule().getCode();
            if (target == null) {
                skipped++;
                notes.add(code + ": not offered this semester.");
                continue;
            }
            if (already.contains(target.getId())) {
                skipped++;
                notes.add(code + ": already has classes this semester.");
                continue;
            }
            if (!old.getVenue().isActive()) {
                skipped++;
                notes.add(code + " " + item(old).when() + ": " + old.getVenue().getCode() + " is no longer in use.");
                continue;
            }
            TimetableSlot s = new TimetableSlot();
            s.setOffering(target);
            s.setKind(old.getKind());
            s.setDayOfWeek(old.getDayOfWeek());
            s.setStartTime(old.getStartTime());
            s.setEndTime(old.getEndTime());
            s.setVenue(old.getVenue());
            boolean stillTeaches = old.getLecturerUserId() != null && target.getStaff().stream().anyMatch(st -> st.getUserId().equals(old.getLecturerUserId()));
            s.setLecturerUserId(stillTeaches ? old.getLecturerUserId() : null);
            s.setGroupLabel(old.getGroupLabel());
            s.setNote(old.getNote());
            try {
                Clashes.checkClass(item(s), items(existing), offs, names);
            } catch (BusinessRuleException e) {
                skipped++;
                notes.add(code + " " + item(s).when() + ": " + e.getMessage());
                continue;
            }
            slots.save(s);
            existing.add(s);
            copied++;
        }
        audit.record("TIMETABLE_COPIED", "Semester", semesterId, Map.of("from", fromSemesterId, "copied", copied, "skipped", skipped));
        return new CopyResult(copied, skipped, notes.size() > 50 ? notes.subList(0, 50) : notes);
    }

    // ------------------------------------------------------------------ publishing

    @Transactional
    public PublishResult publish(Long semesterId, Kind kind, boolean notify) {
        Semester s = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        String by = CurrentUser.name();
        OffsetDateTime now = OffsetDateTime.now();
        if (kind == Kind.CLASS) {
            access.require("publish the class timetable", ACADEMIC);
            if (slots.findBySemester(semesterId).isEmpty()) throw new BusinessRuleException("TIMETABLE_EMPTY", "The class timetable has no classes yet.");
            s.setClassTimetablePublishedAt(now);
            s.setClassTimetablePublishedBy(by);
        } else {
            access.require("publish the examination timetable", EXAMS);
            List<ExamSitting> papers = sittings.findBySemester(semesterId);
            if (papers.isEmpty()) throw new BusinessRuleException("TIMETABLE_EMPTY", "The examination timetable has no papers yet.");
            List<String> clashes = Clashes.examStudentClashes(ExamTimetableService.items(papers), data.offerings(semesterId));
            if (!clashes.isEmpty()) {
                throw new BusinessRuleException("STUDENT_EXAM_CLASH", "Some students would sit two papers at once: " + String.join("; ", clashes)
                        + ". Move one of the papers first.");
            }
            s.setExamTimetablePublishedAt(now);
            s.setExamTimetablePublishedBy(by);
        }
        int sent = 0;
        if (notify) {
            String label = s.getName() + (s.getAcademicYear() == null ? "" : " " + s.getAcademicYear().getCode());
            for (String phone : jdbc.queryForList("""
                    select distinct st.phone from semester_registrations sr join students st on st.id = sr.student_id
                    where sr.semester_id = ? and sr.status = 'APPROVED' and st.phone is not null""", String.class, semesterId)) {
                messaging.event(kind == Kind.CLASS ? "TIMETABLE_PUBLISHED" : "EXAM_TIMETABLE_PUBLISHED", phone, Map.of("semester", label));
                sent++;
            }
        }
        audit.record(kind == Kind.CLASS ? "CLASS_TIMETABLE_PUBLISHED" : "EXAM_TIMETABLE_PUBLISHED", "Semester", semesterId, Map.of("notified", sent));
        return new PublishResult(kind, now, by, sent);
    }

    @Transactional
    public PublishResult withdraw(Long semesterId, Kind kind) {
        Semester s = semesters.findById(semesterId).orElseThrow(() -> new NotFoundException("Semester", semesterId));
        if (kind == Kind.CLASS) {
            access.require("withdraw the class timetable", ACADEMIC);
            s.setClassTimetablePublishedAt(null);
            s.setClassTimetablePublishedBy(null);
        } else {
            access.require("withdraw the examination timetable", EXAMS);
            s.setExamTimetablePublishedAt(null);
            s.setExamTimetablePublishedBy(null);
        }
        audit.record(kind == Kind.CLASS ? "CLASS_TIMETABLE_WITHDRAWN" : "EXAM_TIMETABLE_WITHDRAWN", "Semester", semesterId, Map.of());
        return new PublishResult(kind, null, null, 0);
    }

    // ------------------------------------------------------------------ helpers

    private ModuleOffering offering(Long id) {
        return offerings.findById(id).orElseThrow(() -> new NotFoundException("Module offering", id));
    }

    private void requireEdit(ModuleOffering o) {
        if (access.has(ACADEMIC) || access.moderates(o.getModule())) return;
        throw new AccessDeniedException("Not allowed to change the classes of " + o.getModule().getCode());
    }

    private SlotView single(TimetableSlot s) {
        Long semesterId = s.getOffering().getSemester().getId();
        Map<Long, Offering> offs = data.offerings(semesterId);
        return view(s, offs, names(List.of(s), offs), data.semester(semesterId).classPublishedAt());
    }

    private Map<Long, String> names(List<TimetableSlot> list, Map<Long, Offering> offs) {
        Set<Long> ids = new HashSet<>();
        list.forEach(s -> {
            if (s.getLecturerUserId() != null) ids.add(s.getLecturerUserId());
        });
        Map<Long, String> m = data.names(ids);
        offs.values().forEach(o -> o.lecturers().forEach(p -> m.putIfAbsent(p.id(), p.fullName())));
        return m;
    }

    static SlotView view(TimetableSlot s, Map<Long, Offering> offs, Map<Long, String> names, OffsetDateTime publishedAt) {
        Offering o = offs.get(s.getOffering().getId());
        String lecturer = s.getLecturerUserId() != null ? names.get(s.getLecturerUserId())
                : o == null || o.lecturers().isEmpty() ? null : String.join(", ", o.lecturers().stream().map(p -> p.fullName()).toList());
        return new SlotView(s.getId(), s.getOffering().getId(), s.getOffering().getModule().getCode(), s.getOffering().getModule().getTitle(),
                s.getKind().name(), s.getDayOfWeek(), s.getStartTime(), s.getEndTime(), s.getVenue().getId(), s.getVenue().getCode(),
                s.getVenue().getName(), s.getLecturerUserId(), lecturer, s.getGroupLabel(), s.getNote(), o == null ? 0 : o.students().size(),
                publishedAt != null && s.getChangedAt().isAfter(publishedAt));
    }

    static ClassItem item(TimetableSlot s) {
        return new ClassItem(s.getId(), s.getOffering().getId(), s.getDayOfWeek(), s.getStartTime(), s.getEndTime(), s.getVenue().getId(),
                s.getVenue().getCode(), s.getVenue().getCapacity(), s.getLecturerUserId(), s.getGroupLabel());
    }

    static List<ClassItem> items(List<TimetableSlot> list) {
        return list.stream().map(TimetableService::item).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Offerings by id for the given list (kept in order). */
    static Map<Long, Offering> only(Map<Long, Offering> all, Set<Long> ids) {
        Map<Long, Offering> m = new LinkedHashMap<>();
        all.forEach((k, v) -> {
            if (ids.contains(k)) m.put(k, v);
        });
        return m;
    }
}
