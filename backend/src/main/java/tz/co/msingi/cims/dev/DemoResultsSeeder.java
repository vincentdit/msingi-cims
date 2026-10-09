package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.exams.ExamRepositories.ExamSettingsRepository;
import tz.co.msingi.cims.exams.ExamRepositories.RegulationSetRepository;
import tz.co.msingi.cims.exams.ExamSettings;
import tz.co.msingi.cims.exams.RegulationDefaults;
import tz.co.msingi.cims.exams.RegulationService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * Development only: NTA examination regulations (the SRS's indicative values) from the earliest academic year, and a marks-entry
 * window on the demo semesters that have none, so marks can be entered at once.
 */
@Component
@Order(5)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoResultsSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final RegulationSetRepository regulationSets;
    private final RegulationService regulations;
    private final ExamSettingsRepository settings;
    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (settings.count() == 0) settings.save(new ExamSettings());
        if (regulationSets.count() == 0) {
            AcademicYear first = years.findAll().stream().min(Comparator.comparing(AcademicYear::getStartDate)).orElse(null);
            if (first != null) {
                regulations.create(RegulationDefaults.ntaCertificate(first.getId()));
                regulations.create(RegulationDefaults.ntaDiploma(first.getId()));
            }
        }
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        for (Semester s : semesters.findAll()) {
            if (s.getMarksEntryOpens() == null && !s.getRegistrationOpens().isAfter(today)) {
                s.setMarksEntryOpens(today.minusDays(1));
                s.setMarksEntryCloses(today.plusDays(90));
            }
        }
    }
}
