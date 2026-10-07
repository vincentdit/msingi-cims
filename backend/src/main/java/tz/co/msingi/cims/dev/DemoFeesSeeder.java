package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.finance.fees.FeeItem;
import tz.co.msingi.cims.finance.fees.FeeRepositories.ClearanceGateRepository;
import tz.co.msingi.cims.finance.fees.FeeRepositories.FeeItemRepository;
import tz.co.msingi.cims.finance.fees.FeeRepositories.FeeStructureRepository;
import tz.co.msingi.cims.finance.fees.FeeStructure;
import tz.co.msingi.cims.finance.fees.FeeStructureLine;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Demo fees (dev profile): fee items, approved year-1 fee structures for the demo programmes and the registration
 * clearance rule (first instalment paid). Runs once; edit everything from the Fees pages.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
public class DemoFeesSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final FeeItemRepository items;
    private final FeeStructureRepository structures;
    private final ClearanceGateRepository gates;
    private final AcademicYearRepository years;
    private final ProgrammeRepository programmes;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        tenants.findByCodeIgnoreCase("DEMO").ifPresent(t -> TenantContext.runAs(t.toInfo(), () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (!items.findAll().isEmpty()) return;
        AcademicYear year = years.findAll().stream().filter(AcademicYear::isCurrent).findFirst().orElse(null);
        if (year == null) return;
        Map<String, FeeItem> i = new HashMap<>();
        i.put("REG", item("REG", "Registration fee", FeeItem.Category.REGISTRATION, "140316", 10));
        i.put("CAUTION", item("CAUTION", "Caution money (refundable)", FeeItem.Category.CAUTION, "140399", 20));
        i.put("EXAM", item("EXAM", "Examination fee", FeeItem.Category.EXAMINATION, "140317", 30));
        i.put("UNION", item("UNION", "Students' union", FeeItem.Category.STUDENT_UNION, null, 40));
        i.put("TUITION", item("TUITION", "Tuition fee", FeeItem.Category.TUITION, "140313", 50));
        Map<String, long[]> tuition = Map.of("BTCIT", new long[]{550_000, 550_000}, "ODIT", new long[]{700_000, 700_000},
                "BTCAC", new long[]{500_000, 500_000}, "NVAEI2", new long[]{450_000, 0});
        for (Programme p : programmes.findAll()) {
            long[] t = tuition.getOrDefault(p.getCode(), new long[]{500_000, 500_000});
            FeeStructure f = new FeeStructure();
            f.setAcademicYear(year);
            f.setProgramme(p);
            f.setYearOfStudy((short) 1);
            f.setCreatedByName("Demo data");
            f.setStatus(FeeStructure.Status.APPROVED);
            f.setApprovedAt(OffsetDateTime.now());
            f.setApprovedByName("Bursar (demo data)");
            f.setNotes("Demo fee structure");
            line(f, i.get("REG"), 20_000, null, 1);
            line(f, i.get("CAUTION"), 20_000, null, 1);
            line(f, i.get("EXAM"), 50_000, null, 1);
            line(f, i.get("UNION"), 10_000, null, 1);
            line(f, i.get("TUITION"), t[0], Math.round(t[0] * 1.5), 1);
            if (t[1] > 0) line(f, i.get("TUITION"), t[1], Math.round(t[1] * 1.5), 2);
            structures.save(f);
        }
        ClearanceGate reg = new ClearanceGate();
        reg.setGate(ClearanceGate.Gate.REGISTRATION);
        reg.setMinPercentPaid(BigDecimal.ZERO);
        reg.setFirstInstalmentRequired(true);
        gates.save(reg);
        ClearanceGate exam = new ClearanceGate();
        exam.setGate(ClearanceGate.Gate.EXAMINATION);
        exam.setMinPercentPaid(BigDecimal.valueOf(50));
        gates.save(exam);
        log.info("Demo fees ready - fee structures for {}, registration requires the first instalment", year.getCode());
    }

    private FeeItem item(String code, String name, FeeItem.Category category, String gfs, int priority) {
        FeeItem f = new FeeItem();
        f.setCode(code);
        f.setName(name);
        f.setCategory(category);
        f.setGfsCode(gfs);
        f.setAllocationPriority((short) priority);
        return items.save(f);
    }

    private static void line(FeeStructure f, FeeItem item, long amount, Long foreign, int instalment) {
        FeeStructureLine l = new FeeStructureLine();
        l.setStructure(f);
        l.setFeeItem(item);
        l.setAmount(BigDecimal.valueOf(amount));
        l.setForeignAmount(foreign == null ? null : BigDecimal.valueOf(foreign));
        l.setInstalment((short) instalment);
        f.getLines().add(l);
    }
}
