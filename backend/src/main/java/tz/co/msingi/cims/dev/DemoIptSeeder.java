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
import tz.co.msingi.cims.ipt.IptPeriod;
import tz.co.msingi.cims.ipt.IptRepositories.OrganisationRepository;
import tz.co.msingi.cims.ipt.IptRepositories.PeriodRepository;
import tz.co.msingi.cims.ipt.PlacementOrganisation;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Development only: a few placement organisations and an IPT period, open for student requests, in the current (or latest) semester. */
@Component
@Order(8)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoIptSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final OrganisationRepository organisations;
    private final PeriodRepository periods;
    private final SemesterRepository semesters;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (organisations.count() == 0) {
            organisation("Tanzania Electric Supply Company (TANESCO) - ICT Unit", "Energy", "Dar es Salaam", "Ms. Grace Mushi", "ICT Manager", "0713000101", 3);
            organisation("Vodacom Tanzania - Network Operations", "Telecommunications", "Dar es Salaam", "Mr. Peter Kimaro", "NOC Supervisor", "0754000202", 2);
            organisation("Mbeya Regional Referral Hospital - ICT Section", "Health", "Mbeya", "Mr. Ally Sanga", "Head of ICT", "0767000303", 2);
            organisation("Arusha City Council - IT Department", "Local government", "Arusha", "Ms. Joyce Laizer", "IT Officer", "0688000404", null);
        }
        if (periods.count() > 0) return;
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        List<Semester> all = semesters.findAllByOrderByStartDateDesc();
        Semester sem = all.stream().filter(s -> !s.getStartDate().isAfter(today) && !s.getEndDate().isBefore(today)).findFirst()
                .orElse(all.isEmpty() ? null : all.get(0));
        if (sem == null) return;
        IptPeriod p = new IptPeriod();
        p.setName("IPT " + today.getYear() + " (all years)");
        p.setSemester(sem);
        p.setStartsOn(today.minusDays(14));
        p.setEndsOn(today.plusDays(56));
        p.setRequestsClose(today.plusDays(21));
        p.setMinLogbookWeeks((short) 2);
        p.setCreatedByName("Demo data");
        periods.save(p);
    }

    private void organisation(String name, String sector, String region, String contact, String title, String phone, Integer capacity) {
        PlacementOrganisation o = new PlacementOrganisation();
        o.setName(name);
        o.setSector(sector);
        o.setRegion(region);
        o.setContactName(contact);
        o.setContactTitle(title);
        o.setContactPhone(phone);
        o.setCapacity(capacity);
        organisations.save(o);
    }
}
