package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.hostel.Hostel;
import tz.co.msingi.cims.hostel.HostelBed;
import tz.co.msingi.cims.hostel.HostelFeeCategory;
import tz.co.msingi.cims.hostel.HostelRepositories.FeeCategoryRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.HostelRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.RoomRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.WindowRepository;
import tz.co.msingi.cims.hostel.HostelRoom;
import tz.co.msingi.cims.hostel.HostelWindow;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

/** Development only: two hostels with a few rooms, two fee categories and an open application window for the first academic year. */
@Component
@Order(7)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoHostelSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final FeeCategoryRepository categories;
    private final HostelRepository hostels;
    private final RoomRepository rooms;
    private final WindowRepository windows;
    private final AcademicYearRepository years;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (hostels.count() > 0) return;
        HostelFeeCategory std = category("STD", "Standard (shared)", "450000");
        HostelFeeCategory single = category("SGL", "Single room", "650000");
        Hostel kili = hostel("KILI", "Kilimanjaro Hostel", Hostel.Gender.FEMALE, "North campus");
        Hostel sere = hostel("SERE", "Serengeti Hostel", Hostel.Gender.MALE, "South campus");
        room(kili, "A", 1, "101", HostelRoom.RoomType.DOUBLE, std, 2, true);
        room(kili, "A", 1, "102", HostelRoom.RoomType.DOUBLE, std, 2, false);
        room(kili, "A", 2, "201", HostelRoom.RoomType.SINGLE, single, 1, false);
        room(sere, "B", 1, "101", HostelRoom.RoomType.TRIPLE, std, 3, false);
        room(sere, "B", 1, "102", HostelRoom.RoomType.SINGLE, single, 1, true);
        AcademicYear first = years.findAll().stream().min(Comparator.comparing(AcademicYear::getStartDate)).orElse(null);
        if (first == null) return;
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        HostelWindow w = new HostelWindow();
        w.setAcademicYear(first);
        w.setName("Hostel applications " + first.getCode());
        w.setOpensOn(today.minusDays(1));
        w.setClosesOn(today.plusDays(30));
        w.setPaymentDays((short) 14);
        w.setCreatedByName("Demo data");
        windows.save(w);
    }

    private HostelFeeCategory category(String code, String name, String amount) {
        HostelFeeCategory c = new HostelFeeCategory();
        c.setCode(code);
        c.setName(name);
        c.setAmount(new BigDecimal(amount));
        return categories.save(c);
    }

    private Hostel hostel(String code, String name, Hostel.Gender gender, String location) {
        Hostel h = new Hostel();
        h.setCode(code);
        h.setName(name);
        h.setGender(gender);
        h.setLocation(location);
        return hostels.save(h);
    }

    private void room(Hostel h, String block, int floor, String no, HostelRoom.RoomType type, HostelFeeCategory fee, int beds, boolean accessible) {
        HostelRoom r = new HostelRoom();
        r.setHostel(h);
        r.setBlock(block);
        r.setFloor((short) floor);
        r.setRoomNo(no);
        r.setRoomType(type);
        r.setGender(HostelRoom.Gender.valueOf(h.getGender().name()));
        r.setFeeCategory(fee);
        r.setAccessible(accessible);
        for (int i = 0; i < beds; i++) {
            HostelBed b = new HostelBed();
            b.setRoom(r);
            b.setLabel(String.valueOf((char) ('A' + i)));
            r.getBeds().add(b);
        }
        rooms.save(r);
    }
}
