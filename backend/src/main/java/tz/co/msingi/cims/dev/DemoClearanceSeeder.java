package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.graduation.ClearanceOffice;
import tz.co.msingi.cims.graduation.ClearanceOfficeStaff;
import tz.co.msingi.cims.graduation.GraduationRepositories.ClearanceOfficeRepository;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

/**
 * Development only: the default clearance offices, with the demo librarian on the library and sports offices and the demo warden on
 * the hostel office.
 */
@Component
@Order(6)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoClearanceSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final ClearanceOfficeRepository offices;
    private final UserAccountRepository users;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (offices.count() > 0) return;
        Long library = users.findByUsernameIgnoreCase("library").map(u -> u.getId()).orElse(null);
        Long warden = users.findByUsernameIgnoreCase("warden").map(u -> u.getId()).orElse(null);
        office("HOD", "Head of Department", ClearanceOffice.Kind.DEPARTMENT, 10, "Return departmental equipment and project reports.", null);
        office("LIBRARY", "Library", ClearanceOffice.Kind.STAFF, 20, "Return all borrowed books and settle any library fines.", library);
        office("HOSTEL", "Hostel", ClearanceOffice.Kind.STAFF, 30, "Hand over your room and its inventory to the warden.", warden);
        office("SPORTS", "Sports and games", ClearanceOffice.Kind.STAFF, 40, "Return sports kit and equipment.", library);
        office("FINANCE", "Finance", ClearanceOffice.Kind.FINANCE, 50, "Cleared automatically when you owe nothing.", null);
    }

    private void office(String code, String name, ClearanceOffice.Kind kind, int order, String instructions, Long officer) {
        ClearanceOffice o = new ClearanceOffice();
        o.setCode(code);
        o.setName(name);
        o.setKind(kind);
        o.setSortOrder((short) order);
        o.setInstructions(instructions);
        if (officer != null) {
            ClearanceOfficeStaff s = new ClearanceOfficeStaff();
            s.setOffice(o);
            s.setUserId(officer);
            o.getStaff().add(s);
        }
        offices.save(o);
    }
}
