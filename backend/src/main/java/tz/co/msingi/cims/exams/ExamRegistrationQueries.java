package tz.co.msingi.cims.exams;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;

import java.util.List;

/** Read access to approved semester registrations for examinations. */
public interface ExamRegistrationQueries extends JpaRepository<ModuleRegistration, Long> {

    /** Students taking a module in a semester: approved semester registrations only, by registration number. */
    @Query("""
            select m from ModuleRegistration m join fetch m.registration r join fetch r.student s join fetch s.programme
            where m.module.id = :moduleId and r.semester.id = :semesterId and r.status = :approved
            order by s.registrationNo""")
    List<ModuleRegistration> students(@Param("semesterId") Long semesterId, @Param("moduleId") Long moduleId,
                                      @Param("approved") SemesterRegistration.Status approved);

    @Query("""
            select distinct m.module.id from ModuleRegistration m
            where m.registration.semester.id = :semesterId and m.registration.status = :approved""")
    List<Long> modulesTaken(@Param("semesterId") Long semesterId, @Param("approved") SemesterRegistration.Status approved);

    /** Approved registrations of a class (programme and year of study) in a semester, with their modules. */
    @Query("""
            select distinct r from SemesterRegistration r join fetch r.student s left join fetch r.modules m left join fetch m.module
            where r.semester.id = :semesterId and s.programme.id = :programmeId and r.yearOfStudy = :year and r.status = :approved""")
    List<SemesterRegistration> classOf(@Param("semesterId") Long semesterId, @Param("programmeId") Long programmeId, @Param("year") short year,
                                       @Param("approved") SemesterRegistration.Status approved);

    /** Classes (programme id, year of study, students) with approved registrations in a semester. */
    @Query("""
            select r.student.programme.id, r.yearOfStudy, count(r) from SemesterRegistration r
            where r.semester.id = :semesterId and r.status = :approved group by r.student.programme.id, r.yearOfStudy""")
    List<Object[]> classes(@Param("semesterId") Long semesterId, @Param("approved") SemesterRegistration.Status approved);
}
