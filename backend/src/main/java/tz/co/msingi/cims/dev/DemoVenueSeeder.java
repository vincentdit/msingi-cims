package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.timetable.TeachingVenue;
import tz.co.msingi.cims.timetable.TimetableRepositories.TeachingVenueRepository;

/** Development only: the demo institution's teaching venues, so a timetable can be built at once. */
@Component
@Order(9)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoVenueSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final TeachingVenueRepository venues;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (venues.count() > 0) return;
        venue("LH1", "Lecture Hall 1", TeachingVenue.Kind.LECTURE_HALL, 120, 80);
        venue("LH2", "Lecture Hall 2", TeachingVenue.Kind.LECTURE_HALL, 90, 60);
        venue("CR1", "Classroom 1", TeachingVenue.Kind.CLASSROOM, 45, 30);
        venue("CR2", "Classroom 2", TeachingVenue.Kind.CLASSROOM, 45, 30);
        venue("CLAB", "Computer Laboratory", TeachingVenue.Kind.COMPUTER_LAB, 40, null);
        venue("WS1", "Electrical Workshop", TeachingVenue.Kind.WORKSHOP, 30, null);
    }

    private void venue(String code, String name, TeachingVenue.Kind kind, int capacity, Integer exam) {
        TeachingVenue v = new TeachingVenue();
        v.setCode(code);
        v.setName(name);
        v.setKind(kind);
        v.setCapacity(capacity);
        v.setExamCapacity(exam);
        venues.save(v);
    }
}
