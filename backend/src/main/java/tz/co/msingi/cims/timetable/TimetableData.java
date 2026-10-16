package tz.co.msingi.cims.timetable;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.exams.ExamDtos.Person;
import tz.co.msingi.cims.timetable.TimetableDtos.Cohort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the timetable of a semester is built around: its module offerings with their lecturers, the students registered for each
 * (approved semester registrations), and the programmes and years whose approved curriculum has each module that semester (the
 * planned classes, known before students register).
 */
@Component
@RequiredArgsConstructor
class TimetableData {

    private final JdbcTemplate jdbc;

    record Offering(Long id, Long moduleId, String code, String title, Long departmentId, String departmentName, List<Person> lecturers,
                    Set<Long> students, List<Cohort> cohorts) {

        Set<Long> lecturerIds() {
            Set<Long> ids = new HashSet<>();
            lecturers.forEach(p -> ids.add(p.id()));
            return ids;
        }

        String label() {
            return code + " " + title;
        }
    }

    record Semester(Long id, String name, String yearCode, java.time.LocalDate startDate, java.time.LocalDate endDate, short semesterNo,
                    java.time.OffsetDateTime classPublishedAt, String classPublishedBy, java.time.OffsetDateTime examPublishedAt,
                    String examPublishedBy) {

        String label() {
            return yearCode == null ? name : name + " " + yearCode;
        }
    }

    Semester semester(Long semesterId) {
        List<Semester> s = jdbc.query("""
                select s.id, s.name, y.code, s.start_date, s.end_date, s.semester_no, s.class_timetable_published_at, s.class_timetable_published_by,
                       s.exam_timetable_published_at, s.exam_timetable_published_by
                from semesters s left join academic_years y on y.id = s.academic_year_id where s.id = ?""",
                (r, i) -> new Semester(r.getLong(1), r.getString(2), r.getString(3), r.getObject(4, java.time.LocalDate.class),
                        r.getObject(5, java.time.LocalDate.class), r.getShort(6), r.getObject(7, java.time.OffsetDateTime.class), r.getString(8),
                        r.getObject(9, java.time.OffsetDateTime.class), r.getString(10)), semesterId);
        if (s.isEmpty()) throw new tz.co.msingi.cims.common.exception.NotFoundException("Semester", semesterId);
        return s.get(0);
    }

    /** The semester's offerings by id, in module-code order. */
    Map<Long, Offering> offerings(Long semesterId) {
        Map<Long, List<Person>> staff = new HashMap<>();
        jdbc.query("""
                select st.offering_id, u.id, u.full_name from offering_staff st join user_accounts u on u.id = st.user_id
                join module_offerings o on o.id = st.offering_id where o.semester_id = ? order by u.full_name""",
                r -> {
                    staff.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Person(r.getLong(2), r.getString(3)));
                }, semesterId);
        Map<Long, Set<Long>> students = new HashMap<>();
        jdbc.query("""
                select o.id, mr.student_id from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                join module_offerings o on o.module_id = mr.module_id and o.semester_id = sr.semester_id
                where sr.semester_id = ? and sr.status = 'APPROVED'""",
                r -> {
                    students.computeIfAbsent(r.getLong(1), k -> new HashSet<>()).add(r.getLong(2));
                }, semesterId);
        Map<Long, List<Cohort>> cohorts = new HashMap<>();
        jdbc.query("""
                select distinct o.id, p.id, p.code, cm.year_of_study, cm.category from module_offerings o
                join semesters s on s.id = o.semester_id
                join curriculum_modules cm on cm.module_id = o.module_id and cm.semester_no = s.semester_no
                join curricula c on c.id = cm.curriculum_id and c.status = 'APPROVED'
                join programmes p on p.id = c.programme_id
                where o.semester_id = ? order by p.code, cm.year_of_study""",
                r -> {
                    List<Cohort> l = cohorts.computeIfAbsent(r.getLong(1), k -> new ArrayList<>());
                    Cohort c = new Cohort(r.getLong(2), r.getString(3), r.getInt(4), r.getString(5));
                    // a module in two curriculum versions of one programme and year: keep it once (core wins)
                    l.removeIf(x -> x.programmeId().equals(c.programmeId()) && x.yearOfStudy() == c.yearOfStudy() && "ELECTIVE".equals(x.category()));
                    if (l.stream().noneMatch(x -> x.programmeId().equals(c.programmeId()) && x.yearOfStudy() == c.yearOfStudy())) l.add(c);
                }, semesterId);
        Map<Long, Offering> out = new LinkedHashMap<>();
        jdbc.query("""
                select o.id, m.id, m.code, m.title, m.department_id, d.name from module_offerings o join modules m on m.id = o.module_id
                left join departments d on d.id = m.department_id where o.semester_id = ? order by m.code""",
                r -> {
                    long id = r.getLong(1);
                    Long dept = r.getObject(5) == null ? null : r.getLong(5);
                    out.put(id, new Offering(id, r.getLong(2), r.getString(3), r.getString(4), dept, r.getString(6), staff.getOrDefault(id, List.of()),
                            students.getOrDefault(id, Set.of()), cohorts.getOrDefault(id, List.of())));
                }, semesterId);
        return out;
    }

    /** Staff who can invigilate or teach: active staff accounts, by name. */
    List<Person> staff() {
        return jdbc.query("""
                select id, full_name from user_accounts where user_type = 'STAFF' and status = 'ACTIVE' order by full_name""",
                (r, i) -> new Person(r.getLong(1), r.getString(2)));
    }

    Map<Long, String> names(Set<Long> userIds) {
        Map<Long, String> m = new HashMap<>();
        if (userIds.isEmpty()) return m;
        String in = String.join(",", userIds.stream().map(String::valueOf).toList());
        jdbc.query("select id, full_name from user_accounts where id in (" + in + ")", r -> {
            m.put(r.getLong(1), r.getString(2));
        });
        return m;
    }
}
