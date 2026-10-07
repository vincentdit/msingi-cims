package tz.co.msingi.cims.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.IntakeRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.academic.domain.Intake;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.letter.JoiningInstructions;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Condition;
import tz.co.msingi.cims.admission.eligibility.EntryRules.ConditionType;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Pathway;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomain;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Creates a demo institution ("DEMO") for local development when cims.demo.seed=true.
 * Staff sign-in: admin / Admin@2026, admissions / Admissions@2026, registry / Registry@2026, bursar / Bursar@2026, accountant / Accountant@2026. Safe to run repeatedly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.demo", name = "seed", havingValue = "true")
@org.springframework.core.annotation.Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final UserAccountRepository users;
    private final AcademicYearRepository years;
    private final IntakeRepository intakes;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final AdmissionWindowRepository windows;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;

    @Override
    public void run(ApplicationArguments args) {
        Tenant tenant = tx.execute(s -> tenants.findByCodeIgnoreCase("DEMO").orElseGet(this::createTenant));
        TenantContext.runAs(tenant.toInfo(), () -> tx.executeWithoutResult(s -> seedInstitution()));
        log.info("Demo institution ready - staff: admin / Admin@2026, admissions / Admissions@2026, registry / Registry@2026, bursar / Bursar@2026, accountant / Accountant@2026 (header X-Tenant: DEMO)");
    }

    private Tenant createTenant() {
        Tenant t = new Tenant();
        t.setCode("DEMO");
        t.setName("Msingi Demo Technical College");
        t.setShortName("MDTC");
        t.setNactvetRegNo("REG/DEMO/001");
        t.setOwnership("PRIVATE");
        t.setContactEmail("admissions@demo.ac.tz");
        t.setContactPhone("+255 700 000 000");
        t.setBranding(Map.of("primaryColor", "#1F4E8C", "accentColor", "#12715B",
                "tagline", "Skills for work, skills for life", "logoUrl", ""));
        t = tenants.save(t);
        for (String host : List.of("localhost", "127.0.0.1")) {
            if (!domains.existsByHostIgnoreCase(host)) {
                TenantDomain d = new TenantDomain();
                d.setTenant(t);
                d.setHost(host);
                domains.save(d);
            }
        }
        return t;
    }

    private void seedInstitution() {
        if (users.findByUsernameIgnoreCase("admin").isEmpty()) {
            staff("admin", "System Administrator", "Admin@2026", Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC);
            staff("admissions", "Admissions Officer", "Admissions@2026", Role.ADMISSIONS_OFFICER);
        }
        if (users.findByUsernameIgnoreCase("registry").isEmpty()) {
            staff("registry", "Registry Officer", "Registry@2026", Role.REGISTRAR);
        }
        if (users.findByUsernameIgnoreCase("bursar").isEmpty()) {
            staff("bursar", "Bursar", "Bursar@2026", Role.BURSAR);
            staff("accountant", "Accountant", "Accountant@2026", Role.ACCOUNTANT);
        }
        if (!years.findAll().isEmpty()) {
            backfillNactvetCodes();
            windows.findAll().stream().filter(x -> x.getJoiningInstructions() == null)
                    .forEach(x -> x.setJoiningInstructions(demoJoiningInstructions()));
            return;
        }
        AcademicYear year = new AcademicYear();
        year.setCode("2027/2028");
        year.setStartDate(LocalDate.of(2027, 9, 1));
        year.setEndDate(LocalDate.of(2028, 8, 31));
        year.setCurrent(true);
        years.save(year);

        Intake sept = intake("SEPTEMBER", "September intake", 9);
        intake("MARCH", "March intake", 3);

        Department ict = department("ICT", "Information and Communication Technology");
        Department bus = department("BUS", "Business Studies");
        Department eng = department("ENG", "Electrical Engineering");

        Programme it4 = programme(ict, "BTCIT", "Information Technology", Programme.AwardFamily.NTA, 4, 2, 120);
        Programme it6 = programme(ict, "ODIT", "Information Technology", Programme.AwardFamily.NTA, 6, 6, 80);
        Programme acc4 = programme(bus, "BTCAC", "Accountancy", Programme.AwardFamily.NTA, 4, 2, 100);
        Programme elec = programme(eng, "NVAEI2", "Electrical Installation", Programme.AwardFamily.NVA, 2, 2, 60);

        AdmissionWindow w = new AdmissionWindow();
        w.setAcademicYear(year);
        w.setIntake(sept);
        w.setName("2027/2028 September Intake");
        w.setOpensAt(OffsetDateTime.now().minusDays(1));
        w.setClosesAt(OffsetDateTime.now().plusDays(60));
        w.setApplicationFee(new BigDecimal("10000.00"));
        w.setMaxChoices((short) 3);
        w.setStatus(AdmissionWindow.Status.PUBLISHED);
        w.setInstructions("Have your Form Four (CSEE) index number ready. Application fee TZS 10,000 is paid through a GePG control number.");
        addProgramme(w, it4, 120, new EntryRules("Four passes in CSEE including Basic Mathematics, or NVA Level III",
                List.of(new Pathway("Form Four (CSEE)", List.of(
                                new Condition(ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                                new Condition(ConditionType.CSEE_SUBJECT, 1, "D", List.of("BASIC MATHEMATICS"), null, null))),
                        new Pathway("NVA Level III holders", List.of(
                                new Condition(ConditionType.PRIOR_AWARD, null, null, null, "NVA", 3))))));
        addProgramme(w, it6, 80, new EntryRules("Four passes in CSEE including Basic Mathematics and Physics, or NTA Level 5 in IT",
                List.of(new Pathway("Form Four (CSEE)", List.of(
                                new Condition(ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                                new Condition(ConditionType.CSEE_SUBJECT, 2, "D", List.of("BASIC MATHEMATICS", "PHYSICS"), null, null))),
                        new Pathway("NTA Level 5 holders", List.of(
                                new Condition(ConditionType.PRIOR_AWARD, null, null, null, "NTA", 5))))));
        addProgramme(w, acc4, 100, new EntryRules("Four passes in CSEE including Basic Mathematics",
                List.of(new Pathway("Form Four (CSEE)", List.of(
                        new Condition(ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                        new Condition(ConditionType.CSEE_SUBJECT, 1, "D", List.of("BASIC MATHEMATICS"), null, null))))));
        addProgramme(w, elec, 60, EntryRules.openEntry("Completed Standard Seven or Form Four"));
        w.setJoiningInstructions(demoJoiningInstructions());
        windows.save(w);
    }

    private void staff(String username, String name, String password, Role... roles) {
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setFullName(name);
        u.setEmail(username + "@demo.ac.tz");
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setUserType(UserAccount.UserType.STAFF);
        u.setStatus(UserAccount.Status.ACTIVE);
        for (Role r : roles) {
            u.grant(r);
        }
        users.save(u);
    }

    private Intake intake(String code, String name, int month) {
        Intake i = new Intake();
        i.setCode(code);
        i.setName(name);
        i.setStartMonth((short) month);
        return intakes.save(i);
    }

    private Department department(String code, String name) {
        Department d = new Department();
        d.setCode(code);
        d.setName(name);
        return departments.save(d);
    }

    private Programme programme(Department d, String code, String field, Programme.AwardFamily family, int level, int semesters, int capacity) {
        Programme p = new Programme();
        p.setDepartment(d);
        p.setCode(code);
        String award = Programme.defaultAwardName(family, level);
        p.setName((family == Programme.AwardFamily.NVA ? "NVA Level " + "I".repeat(level) + " in " : award.replaceAll(" \\(NTA Level \\d\\)", "") + " in ") + field);
        p.setAwardFamily(family);
        p.setAwardLevel((short) level);
        p.setAwardName(award);
        p.setDurationSemesters((short) semesters);
        p.setAccreditedCapacity(capacity);
        p.setNactvetCode(nactvetCode(code));
        return programmes.save(p);
    }

    /** Example joining instructions so admission letters can be issued in the demo; edit them on the window page. */
    private static JoiningInstructions demoJoiningInstructions() {
        return new JoiningInstructions(LocalDate.of(2027, 9, 27), "08:00", "Main Hall, Msingi Demo Technical College",
                LocalDate.of(2027, 10, 8),
                List.of("This admission letter", "Original CSEE (and ACSEE, if any) certificates and a copy of each",
                        "Original birth certificate and a copy", "Four recent passport-size photographs", "National ID (NIDA) number, if available",
                        "Completed medical examination form"),
                List.of("Two sets of the college uniform (available at the college shop)", "Laboratory coat and safety boots (technical programmes)",
                        "Scientific calculator", "Bedding and personal items (if you are allocated a hostel place)"),
                "Fees are payable only through a GePG control number issued by the college after registration.\n"
                        + "The first instalment must be paid before registration is completed.",
                List.of("Orientation for new students runs during the first week.",
                        "Hostel places are limited and allocated on a first-come, first-served basis."),
                "Dr. Amina Salum", "Principal");
    }

    /** Illustrative NACTVET programme codes for the demo (NACTVET verification needs one per programme). */
    private static String nactvetCode(String code) {
        return "DEMO-" + code;
    }

    /** Databases seeded before NACTVET verification existed have programmes without codes. */
    private void backfillNactvetCodes() {
        programmes.findAll().stream().filter(p -> p.getNactvetCode() == null || p.getNactvetCode().isBlank())
                .forEach(p -> p.setNactvetCode(nactvetCode(p.getCode())));
    }

    private static void addProgramme(AdmissionWindow w, Programme p, int capacity, EntryRules rules) {
        WindowProgramme wp = new WindowProgramme();
        wp.setWindow(w);
        wp.setProgramme(p);
        wp.setCapacity(capacity);
        wp.setEntryRules(rules);
        w.getProgrammes().add(wp);
    }
}
