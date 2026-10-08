package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.curriculum.Curriculum;
import tz.co.msingi.cims.academic.curriculum.CurriculumModule;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.ModuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.ElectiveRule;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Development only: modules, approved curricula for BTCIT and ODIT (with one module shared by both), and the semesters of
 * 2027/2028 with Semester 1 registration open now, so semester registration can be tried at once.
 */
@Component
@Order(4)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoCurriculumSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final ModuleRepository modules;
    private final CurriculumRepository curricula;
    private final SemesterRepository semesters;
    private final ProgrammeRepository programmes;
    private final AcademicYearRepository years;
    private final TransactionTemplate tx;

    private record M(String code, String title, double credits, int level, int year, int sem, boolean elective) {
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (modules.count() > 0) return;
        AcademicYear year = years.findByCode("2027/2028").orElse(null);
        if (year == null) return;
        Map<String, Module> made = new HashMap<>();
        List<M> btcit = List.of(
                new M("CSU 04101", "Computer Literacy and Office Applications", 10, 4, 1, 1, false),
                new M("ITU 04102", "Introduction to Computer Hardware", 12, 4, 1, 1, false),
                new M("ITU 04103", "Fundamentals of Programming", 12, 4, 1, 1, false),
                new M("GSU 04104", "Communication Skills", 6, 4, 1, 1, false),
                new M("ITU 04201", "Computer Networking Basics", 12, 4, 1, 2, false),
                new M("ITU 04202", "Web Page Design", 10, 4, 1, 2, false),
                new M("ITU 04203", "Database Fundamentals", 10, 4, 1, 2, false),
                new M("ITU 04204", "Graphics Design", 8, 4, 1, 2, true),
                new M("ITU 04205", "Computer Maintenance Workshop", 8, 4, 1, 2, true));
        List<M> odit = new java.util.ArrayList<>(List.of(
                new M("CSU 04101", "Computer Literacy and Office Applications", 10, 4, 1, 1, false),
                new M("ITU 05101", "Object-Oriented Programming", 12, 5, 1, 1, false),
                new M("ITU 05102", "Data Communication and Networks", 12, 5, 1, 1, false),
                new M("GSU 05103", "Business Communication", 6, 5, 1, 1, false),
                new M("ITU 05104", "Linux Administration", 8, 5, 1, 1, true),
                new M("ITU 05105", "Computer Graphics", 8, 5, 1, 1, true),
                new M("ITU 05201", "Database Management Systems", 12, 5, 1, 2, false),
                new M("ITU 05202", "Web Application Development", 12, 5, 1, 2, false),
                new M("ITU 05203", "Systems Analysis and Design", 10, 5, 1, 2, false)));
        String[][] later = {
                {"ITU 06101", "Software Engineering"}, {"ITU 06102", "Mobile Application Development"}, {"ITU 06103", "Network Security"},
                {"ITU 06201", "Distributed Systems"}, {"ITU 06202", "IT Project Management"}, {"ITU 06203", "Cloud Computing"},
                {"ITU 06301", "Information Systems Audit"}, {"ITU 06302", "Data Analytics"}, {"ITU 06303", "Entrepreneurship in ICT"},
                {"ITU 06401", "Final Project"}, {"ITU 06402", "Industrial Practical Training Report"}, {"ITU 06403", "Professional Ethics in ICT"}};
        for (int i = 0; i < later.length; i++) {
            int slot = 2 + i / 3; // year 2 sem 1 .. year 3 sem 2
            odit.add(new M(later[i][0], later[i][1], 12, 6, 1 + slot / 2, slot % 2 + 1, false));
        }
        curriculum("BTCIT", btcit, year, made, Map.of("1/2", new short[]{1, 1}));
        curriculum("ODIT", odit, year, made, Map.of("1/1", new short[]{1, 1}));

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        semester(year, 1, "Semester 1", LocalDate.of(2027, 9, 27), LocalDate.of(2028, 2, 11), today.minusDays(2), today.plusDays(28), today.plusDays(42));
        semester(year, 2, "Semester 2", LocalDate.of(2028, 3, 6), LocalDate.of(2028, 7, 28), LocalDate.of(2028, 2, 21), LocalDate.of(2028, 3, 17), LocalDate.of(2028, 3, 31));
    }

    private void curriculum(String programmeCode, List<M> list, AcademicYear year, Map<String, Module> made, Map<String, short[]> rules) {
        Programme p = programmes.findAll().stream().filter(x -> x.getCode().equals(programmeCode)).findFirst().orElse(null);
        if (p == null) return;
        Curriculum c = new Curriculum();
        c.setProgramme(p);
        c.setCurriculumVersion((short) 1);
        c.setEffectiveYear(year);
        c.setStatus(Curriculum.Status.APPROVED);
        c.setCreatedByName("Demo data");
        c.setApprovedAt(OffsetDateTime.now());
        c.setApprovedByName("Demo data");
        for (M m : list) {
            Module mod = made.computeIfAbsent(m.code(), k -> {
                Module x = new Module();
                x.setCode(m.code());
                x.setTitle(m.title());
                x.setCredits(BigDecimal.valueOf(m.credits()));
                x.setNtaLevel((short) m.level());
                x.setAssessmentType(Module.AssessmentType.GRADED);
                x.setDepartment(p.getDepartment());
                return modules.save(x);
            });
            CurriculumModule cm = new CurriculumModule();
            cm.setCurriculum(c);
            cm.setModule(mod);
            cm.setYearOfStudy((short) m.year());
            cm.setSemesterNo((short) m.sem());
            cm.setCategory(m.elective() ? CurriculumModule.Category.ELECTIVE : CurriculumModule.Category.CORE);
            c.getModules().add(cm);
        }
        rules.forEach((slot, mm) -> {
            ElectiveRule r = new ElectiveRule();
            r.setCurriculum(c);
            r.setYearOfStudy(Short.parseShort(slot.split("/")[0]));
            r.setSemesterNo(Short.parseShort(slot.split("/")[1]));
            r.setMinElectives(mm[0]);
            r.setMaxElectives(mm[1]);
            c.getElectiveRules().add(r);
        });
        curricula.save(c);
    }

    private void semester(AcademicYear y, int no, String name, LocalDate start, LocalDate end, LocalDate opens, LocalDate closes, LocalDate late) {
        Semester s = new Semester();
        s.setAcademicYear(y);
        s.setSemesterNo((short) no);
        s.setName(name);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setRegistrationOpens(opens);
        s.setRegistrationCloses(closes);
        s.setLateRegistrationCloses(late);
        semesters.save(s);
    }
}
