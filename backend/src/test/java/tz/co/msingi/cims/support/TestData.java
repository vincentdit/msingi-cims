package tz.co.msingi.cims.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Builds an isolated institution with a staff user and an open admission window. */
@TestComponent
public class TestData {

    public static final String STAFF_PASSWORD = "Staff@12345";

    @Autowired TenantRepository tenants;
    @Autowired UserAccountRepository users;
    @Autowired AcademicYearRepository years;
    @Autowired IntakeRepository intakes;
    @Autowired DepartmentRepository departments;
    @Autowired ProgrammeRepository programmes;
    @Autowired AdmissionWindowRepository windows;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate tx;

    public record Institution(Tenant tenant, Long windowId, Long windowProgrammeId, Long programmeId) {
        public TenantContext.TenantInfo info() {
            return tenant.toInfo();
        }
    }

    /** Adds a staff user to an existing institution. */
    public void staff(Tenant tenant, String username, Role... roles) {
        TenantContext.runAs(tenant.toInfo(), () -> tx.executeWithoutResult(s -> {
            UserAccount u = new UserAccount();
            u.setUsername(username);
            u.setFullName("Staff " + username);
            u.setPasswordHash(passwordEncoder.encode(STAFF_PASSWORD));
            u.setUserType(UserAccount.UserType.STAFF);
            u.setStatus(UserAccount.Status.ACTIVE);
            for (Role r : roles) u.grant(r);
            users.save(u);
        }));
    }

    public Institution institution(String code, BigDecimal fee) {
        Tenant t = tx.execute(s -> {
            Tenant x = new Tenant();
            x.setCode(code);
            x.setName(code + " Technical College");
            return tenants.save(x);
        });
        return TenantContext.callAs(t.toInfo(), () -> tx.execute(s -> {
            UserAccount u = new UserAccount();
            u.setUsername("officer");
            u.setFullName("Officer " + code);
            u.setPasswordHash(passwordEncoder.encode(STAFF_PASSWORD));
            u.setUserType(UserAccount.UserType.STAFF);
            u.setStatus(UserAccount.Status.ACTIVE);
            u.grant(Role.ADMISSIONS_OFFICER);
            u.grant(Role.INSTITUTION_ADMIN);
            users.save(u);

            AcademicYear y = new AcademicYear();
            y.setCode("2027/2028");
            y.setStartDate(LocalDate.of(2027, 9, 1));
            y.setEndDate(LocalDate.of(2028, 8, 31));
            years.save(y);
            Intake i = new Intake();
            i.setCode("SEPTEMBER");
            i.setName("September intake");
            i.setStartMonth((short) 9);
            intakes.save(i);
            Department d = new Department();
            d.setCode("ICT");
            d.setName("ICT " + code);
            departments.save(d);
            Programme p = new Programme();
            p.setDepartment(d);
            p.setCode("BTCIT");
            p.setName("Basic Technician Certificate in IT (" + code + ")");
            p.setAwardFamily(Programme.AwardFamily.NTA);
            p.setAwardLevel((short) 4);
            p.setAwardName(Programme.defaultAwardName(Programme.AwardFamily.NTA, 4));
            p.setDurationSemesters((short) 2);
            p.setAccreditedCapacity(100);
            programmes.save(p);

            AdmissionWindow w = new AdmissionWindow();
            w.setAcademicYear(y);
            w.setIntake(i);
            w.setName("Test window " + code);
            w.setOpensAt(OffsetDateTime.now().minusDays(1));
            w.setClosesAt(OffsetDateTime.now().plusDays(30));
            w.setApplicationFee(fee);
            w.setStatus(AdmissionWindow.Status.PUBLISHED);
            WindowProgramme wp = new WindowProgramme();
            wp.setWindow(w);
            wp.setProgramme(p);
            wp.setCapacity(50);
            wp.setEntryRules(new EntryRules("Four CSEE passes incl. Basic Mathematics", List.of(
                    new EntryRules.Pathway("CSEE", List.of(
                            new EntryRules.Condition(EntryRules.ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                            new EntryRules.Condition(EntryRules.ConditionType.CSEE_SUBJECT, 1, "D", List.of("BASIC MATHEMATICS"), null, null))))));
            w.getProgrammes().add(wp);
            windows.save(w);
            return new Institution(t, w.getId(), wp.getId(), p.getId());
        }));
    }
}
