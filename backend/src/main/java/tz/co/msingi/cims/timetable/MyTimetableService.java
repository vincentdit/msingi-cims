package tz.co.msingi.cims.timetable;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.timetable.TimetableData.Offering;
import tz.co.msingi.cims.timetable.TimetableDtos.MyExam;
import tz.co.msingi.cims.timetable.TimetableDtos.MyTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.SemesterOption;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingView;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotView;
import tz.co.msingi.cims.timetable.TimetableDtos.StudentTimetable;
import tz.co.msingi.cims.timetable.TimetableRepositories.ExamSittingRepository;
import tz.co.msingi.cims.timetable.TimetableRepositories.TimetableSlotRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Personal timetables: a lecturer's classes, the papers of their modules and the papers they invigilate (whether published or not);
 * a student's classes and papers for the modules of their semester registration, once each timetable is published.
 */
@Service
@RequiredArgsConstructor
public class MyTimetableService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final TimetableData data;
    private final TimetableSlotRepository slots;
    private final ExamSittingRepository sittings;
    private final StudentRepository students;

    @Transactional(readOnly = true)
    public MyTimetable lecturer(Long semesterId) {
        Long me = CurrentUser.requireId();
        List<SemesterOption> semesters = jdbc.query("""
                select distinct s.id, s.name, y.code, s.start_date, s.end_date from semesters s left join academic_years y on y.id = s.academic_year_id
                where s.id in (select o.semester_id from offering_staff st join module_offerings o on o.id = st.offering_id where st.user_id = ?)
                   or s.id in (select o.semester_id from timetable_slots t join module_offerings o on o.id = t.offering_id where t.lecturer_user_id = ?)
                   or s.id in (select o.semester_id from exam_invigilators i join exam_sittings x on x.id = i.sitting_id
                               join module_offerings o on o.id = x.offering_id where i.user_id = ?)
                order by s.start_date desc""",
                (r, i) -> new SemesterOption(r.getLong(1), r.getString(2), r.getString(3), r.getObject(4, LocalDate.class), r.getObject(5, LocalDate.class)),
                me, me, me);
        if (semesters.isEmpty()) return new MyTimetable(List.of(), null, false, false, List.of(), List.of());
        SemesterOption sem = pick(semesters, semesterId);
        TimetableData.Semester s = data.semester(sem.id());
        Map<Long, Offering> offs = data.offerings(sem.id());
        Set<Long> mine = new HashSet<>();
        offs.values().forEach(o -> {
            if (o.lecturerIds().contains(me)) mine.add(o.id());
        });
        List<TimetableSlot> all = slots.findBySemester(sem.id());
        Set<Long> ids = new HashSet<>();
        all.forEach(t -> {
            if (t.getLecturerUserId() != null) ids.add(t.getLecturerUserId());
        });
        Map<Long, String> names = data.names(ids);
        offs.values().forEach(o -> o.lecturers().forEach(p -> names.putIfAbsent(p.id(), p.fullName())));
        List<SlotView> classes = all.stream().filter(t -> me.equals(t.getLecturerUserId()) || (t.getLecturerUserId() == null && mine.contains(t.getOffering().getId())))
                .map(t -> TimetableService.view(t, offs, names, s.classPublishedAt())).toList();
        List<ExamSitting> papers = sittings.findBySemester(sem.id());
        Set<Long> invIds = new HashSet<>();
        papers.forEach(p -> p.getInvigilators().forEach(i -> invIds.add(i.getUserId())));
        Map<Long, String> invNames = data.names(invIds);
        List<MyExam> exams = new ArrayList<>();
        for (ExamSitting p : papers) {
            boolean invigilating = p.getInvigilators().stream().anyMatch(i -> i.getUserId().equals(me));
            if (invigilating || mine.contains(p.getOffering().getId())) {
                exams.add(new MyExam(ExamTimetableService.view(p, offs, invNames, s.examPublishedAt()), invigilating));
            }
        }
        return new MyTimetable(semesters, sem, s.classPublishedAt() != null, s.examPublishedAt() != null, classes, exams);
    }

    @Transactional(readOnly = true)
    public StudentTimetable student(Long semesterId) {
        Student me = students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        List<Map<String, Object>> regs = jdbc.queryForList("""
                select sr.id, sr.semester_id from semester_registrations sr join semesters s on s.id = sr.semester_id
                where sr.student_id = ? and sr.status in ('SUBMITTED', 'APPROVED') order by s.start_date desc, s.id desc""", me.getId());
        Map<String, Object> reg = regs.stream().filter(r -> semesterId == null || semesterId.equals(((Number) r.get("semester_id")).longValue()))
                .findFirst().orElse(null);
        if (reg == null) return new StudentTimetable(null, null, false, null, false, null, List.of(), List.of(), List.of());
        long semId = ((Number) reg.get("semester_id")).longValue();
        TimetableData.Semester s = data.semester(semId);
        Set<Long> moduleIds = new HashSet<>(jdbc.queryForList("select module_id from module_registrations where semester_registration_id = ?", Long.class,
                ((Number) reg.get("id")).longValue()));
        Map<Long, Offering> offs = data.offerings(semId);
        Set<Long> mine = new HashSet<>();
        offs.values().forEach(o -> {
            if (moduleIds.contains(o.moduleId())) mine.add(o.id());
        });
        List<SlotView> classes = List.of();
        List<String> unscheduled = List.of();
        if (s.classPublishedAt() != null) {
            List<TimetableSlot> all = slots.findBySemester(semId).stream().filter(t -> mine.contains(t.getOffering().getId())).toList();
            Set<Long> ids = new HashSet<>();
            all.forEach(t -> {
                if (t.getLecturerUserId() != null) ids.add(t.getLecturerUserId());
            });
            Map<Long, String> names = data.names(ids);
            classes = all.stream().map(t -> TimetableService.view(t, offs, names, s.classPublishedAt())).toList();
            Set<Long> taught = new HashSet<>();
            all.forEach(t -> taught.add(t.getOffering().getId()));
            unscheduled = mine.stream().filter(id -> !taught.contains(id)).map(id -> offs.get(id).code()).sorted().toList();
        }
        List<SittingView> exams = List.of();
        if (s.examPublishedAt() != null) {
            exams = sittings.findBySemester(semId).stream().filter(p -> mine.contains(p.getOffering().getId()))
                    .map(p -> ExamTimetableService.view(p, offs, Map.of(), s.examPublishedAt()))
                    .map(v -> new SittingView(v.id(), v.offeringId(), v.moduleCode(), v.moduleTitle(), v.paper(), v.date(), v.startTime(), v.endTime(),
                            v.venues(), List.of(), v.candidates(), v.seats(), v.note(), v.changedSincePublished()))   // invigilators are not shown to students
                    .toList();
        }
        return new StudentTimetable(semId, s.label(), s.classPublishedAt() != null, s.classPublishedAt(), s.examPublishedAt() != null, s.examPublishedAt(),
                classes, exams, unscheduled);
    }

    private static SemesterOption pick(List<SemesterOption> semesters, Long semesterId) {
        if (semesterId != null) {
            return semesters.stream().filter(s -> s.id().equals(semesterId)).findFirst().orElseThrow(() -> new NotFoundException("Semester", semesterId));
        }
        LocalDate today = LocalDate.now(ZONE);
        return semesters.stream().filter(s -> !s.startDate().isAfter(today) && !s.endDate().isBefore(today)).findFirst()
                .orElse(semesters.stream().filter(s -> s.startDate().isAfter(today)).reduce((a, b) -> b).orElse(semesters.get(0)));
    }
}
