package tz.co.msingi.cims.console.institutions;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.AdminRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.AdminView;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.DomainRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.DomainView;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.InstitutionDetail;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.InstitutionRow;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.IssuedAdmin;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.OnboardRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.Overview;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.SetupStep;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.SmsCreditRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.UpdateRequest;
import tz.co.msingi.cims.console.institutions.InstitutionDtos.Usage;
import tz.co.msingi.cims.integration.log.IntegrationLogQuery;
import tz.co.msingi.cims.notification.MessagingAdminService;
import tz.co.msingi.cims.notification.MessagingDtos.TopupRequest;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.SessionGuard;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantDomain;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantLookupService;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.platform.tenancy.TenantWork;
import tz.co.msingi.cims.platform.users.StaffUserService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The institutions on the platform (Msingi's view): onboarding with domains and a first administrator, details, domains,
 * suspension and reactivation, the institution's administrators, SMS credit, usage and setup progress.
 *
 * Platform tables (institutions, domains, the platform audit trail) are written as the platform; anything inside an
 * institution is done as that institution, so its row-level security applies and its own audit trail records the action
 * as done by Msingi platform staff.
 */
@Service
@RequiredArgsConstructor
public class InstitutionService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Pattern HOST = Pattern.compile("^(?=.{1,190}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern NO_CONTROL = Pattern.compile("^[^\\p{Cntrl}]*$");

    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final TenantLookupService lookup;
    private final TenantWork work;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final SessionGuard guard;
    private final AuditService audit;
    private final PlatformAuditService platformAudit;
    private final IntegrationLogQuery integrations;
    private final MessagingAdminService messaging;

    @Value("${cims.platform.staff-login-url:https://{host}/staff/login}")
    private String staffLoginUrl;

    // ------------------------------------------------------------------ reading

    public Overview overview() {
        List<InstitutionRow> rows = list();
        long active = rows.stream().filter(r -> "ACTIVE".equals(r.status())).count();
        long suspended = rows.stream().filter(r -> "SUSPENDED".equals(r.status())).count();
        List<InstitutionRow> attention = rows.stream()
                .filter(r -> "SUSPENDED".equals(r.status()) || r.integrationFailures24h() > 0 || r.setupDone() < r.setupTotal())
                .sorted(Comparator.comparing((InstitutionRow r) -> !"SUSPENDED".equals(r.status()))
                        .thenComparing(Comparator.comparingLong(InstitutionRow::integrationFailures24h).reversed()))
                .limit(10).toList();
        return tx.execute(s -> new Overview(rows.size(), active, suspended,
                rows.stream().mapToLong(r -> r.usage().studentsActive()).sum(),
                rows.stream().mapToLong(r -> r.usage().staffActive()).sum(),
                rows.stream().mapToLong(r -> r.usage().applicationsThisYear()).sum(),
                integrations.health(null, 24), attention, platformAudit.page(null, null, 0, 8).content()));
    }

    public List<InstitutionRow> list() {
        List<Tenant> all = tx.execute(s -> tenants.findAll(org.springframework.data.domain.Sort.by("name")));
        Map<Long, Long> failures = tx.execute(s -> integrations.failuresByTenant(24));
        Map<Long, String> hosts = tx.execute(s -> {
            Map<Long, String> m = new HashMap<>();
            for (Tenant t : all) domains.findFirstByTenantIdOrderByIdAsc(t.getId()).ifPresent(d -> m.put(t.getId(), d.getHost()));
            return m;
        });
        List<InstitutionRow> out = new ArrayList<>();
        for (Tenant t : all) {
            Usage u = usage(t);
            List<SetupStep> setup = setup(t, u, hosts.containsKey(t.getId()));
            out.add(new InstitutionRow(t.getId(), t.getCode(), t.getName(), t.getShortName(), t.getStatus(), t.getStatusReason(), t.getOwnership(),
                    t.getNactvetRegNo(), hosts.get(t.getId()), t.getCreatedAt(), u, (int) setup.stream().filter(SetupStep::done).count(),
                    setup.size(), failures.getOrDefault(t.getId(), 0L)));
        }
        return out;
    }

    public InstitutionDetail detail(Long id) {
        Tenant t = tx.execute(s -> tenant(id));
        List<DomainView> ds = tx.execute(s -> domainsOf(t));
        Usage u = usage(t);
        List<AdminView> admins = work.read(t, () -> users.findStaff().stream().filter(x -> x.hasRole(Role.INSTITUTION_ADMIN))
                .sorted(Comparator.comparing(UserAccount::getFullName)).map(InstitutionService::adminView).toList());
        return tx.execute(s -> new InstitutionDetail(t.getId(), t.getCode(), t.getName(), t.getShortName(), t.getNactvetRegNo(), t.getOwnership(),
                t.getStatus(), t.getStatusReason(), t.getStatusChangedAt(), t.getStatusChangedBy(), t.getContactEmail(), t.getContactPhone(),
                colour(t), t.getNotes(), t.getOnboardedBy(), t.getCreatedAt(), signInUrl(ds), ds, admins, u, setup(t, u, !ds.isEmpty()),
                integrations.health(t.getId(), 168), platformAudit.page(t.getId(), null, 0, 10).content()));
    }

    // ------------------------------------------------------------------ onboarding

    /** Creates the institution, its domains and its first administrator together; all are kept or none. */
    public IssuedAdmin onboard(OnboardRequest req) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        List<String> hosts = req.domains().stream().map(InstitutionService::normaliseHost).distinct().toList();
        AdminRequest a = req.admin();
        String username = a.username().trim().toLowerCase(Locale.ROOT);
        String temporary = StaffUserService.temporaryPassword();
        Long id = tx.execute(s -> {
            if (tenants.findByCodeIgnoreCase(code).isPresent()) {
                throw new BusinessRuleException("CODE_TAKEN", "An institution with the code " + code + " already exists.");
            }
            for (String h : hosts) requireFreeHost(h);
            Tenant t = new Tenant();
            t.setCode(code);
            t.setName(clean(req.name()));
            t.setShortName(blank(req.shortName()));
            t.setNactvetRegNo(blank(req.nactvetRegNo()));
            t.setOwnership(req.ownership());
            t.setContactEmail(blank(req.contactEmail()));
            t.setContactPhone(blank(req.contactPhone()));
            t.setNotes(blank(req.notes()));
            t.setStatus("ACTIVE");
            t.setOnboardedBy(CurrentUser.platformName());
            Map<String, Object> branding = new HashMap<>();
            branding.put("primaryColor", req.primaryColor() == null || req.primaryColor().isBlank() ? "#1F4E8C" : req.primaryColor());
            t.setBranding(branding);
            tenants.saveAndFlush(t);
            for (String h : hosts) addDomain(t, h, "ALL");
            Long adminId = work.within(t, () -> {
                UserAccount u = newAdmin(username, a, temporary);
                audit.record("INSTITUTION_ONBOARDED", "Tenant", t.getId(), Map.of("code", code, "domains", hosts));
                audit.record("CREATE", "StaffUser", u.getId(), Map.of("username", username, "roles", List.of(Role.INSTITUTION_ADMIN.name())));
                return u.getId();
            });
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", code);
            d.put("name", t.getName());
            d.put("domains", hosts);
            d.put("administrator", username);
            platformAudit.record("INSTITUTION_ONBOARDED", t.getId(), "Tenant", t.getId(), d);
            platformAudit.record("INSTITUTION_ADMIN_CREATED", t.getId(), "StaffUser", adminId, Map.of("username", username));
            return t.getId();
        });
        lookup.evictAll();
        InstitutionDetail detail = detail(id);
        return new IssuedAdmin(detail, username, temporary, detail.staffSignInUrl());
    }

    public InstitutionDetail update(Long id, UpdateRequest req) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenant(id);
            Map<String, Object> changes = new LinkedHashMap<>();
            note(changes, "name", t.getName(), clean(req.name()));
            note(changes, "shortName", t.getShortName(), blank(req.shortName()));
            note(changes, "nactvetRegNo", t.getNactvetRegNo(), blank(req.nactvetRegNo()));
            note(changes, "ownership", t.getOwnership(), req.ownership());
            note(changes, "contactEmail", t.getContactEmail(), blank(req.contactEmail()));
            note(changes, "contactPhone", t.getContactPhone(), blank(req.contactPhone()));
            String colour = req.primaryColor() == null || req.primaryColor().isBlank() ? colour(t) : req.primaryColor();
            note(changes, "primaryColor", colour(t), colour);
            boolean notesChanged = !java.util.Objects.equals(t.getNotes(), blank(req.notes()));
            t.setName(clean(req.name()));
            t.setShortName(blank(req.shortName()));
            t.setNactvetRegNo(blank(req.nactvetRegNo()));
            t.setOwnership(req.ownership());
            t.setContactEmail(blank(req.contactEmail()));
            t.setContactPhone(blank(req.contactPhone()));
            t.setNotes(blank(req.notes()));
            Map<String, Object> branding = new HashMap<>(t.getBranding() == null ? Map.of() : t.getBranding());
            branding.put("primaryColor", colour);
            t.setBranding(branding);
            tenants.saveAndFlush(t);
            if (notesChanged) changes.put("notes", "changed");
            if (!changes.isEmpty()) platformAudit.record("INSTITUTION_UPDATED", t.getId(), "Tenant", t.getId(), changes);
        });
        lookup.evictAll();
        return detail(id);
    }

    // ------------------------------------------------------------------ domains

    public InstitutionDetail addDomain(Long id, DomainRequest req) {
        String host = normaliseHost(req.host());
        tx.executeWithoutResult(s -> {
            Tenant t = tenant(id);
            requireFreeHost(host);
            TenantDomain d = addDomain(t, host, req.purpose() == null ? "ALL" : req.purpose());
            platformAudit.record("DOMAIN_ADDED", t.getId(), "TenantDomain", d.getId(), Map.of("host", host, "purpose", d.getPurpose()));
        });
        lookup.evictAll();
        return detail(id);
    }

    public InstitutionDetail removeDomain(Long id, Long domainId) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenant(id);
            TenantDomain d = domains.findById(domainId).filter(x -> x.getTenant().getId().equals(t.getId()))
                    .orElseThrow(() -> new NotFoundException("Domain not found"));
            long count = domains.findAll().stream().filter(x -> x.getTenant().getId().equals(t.getId())).count();
            if (count <= 1) {
                throw new BusinessRuleException("LAST_DOMAIN", "An institution keeps at least one address. Add the new address before removing this one.");
            }
            domains.delete(d);
            platformAudit.record("DOMAIN_REMOVED", t.getId(), "TenantDomain", domainId, Map.of("host", d.getHost()));
        });
        lookup.evictAll();
        return detail(id);
    }

    // ------------------------------------------------------------------ status

    /** Suspends the institution: its portals and API answer "temporarily unavailable"; data is kept and payments are still received. */
    public InstitutionDetail suspend(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("REASON_REQUIRED", "Give the reason for suspending the institution.");
        }
        changeStatus(id, "SUSPENDED", reason.strip(), "INSTITUTION_SUSPENDED");
        return detail(id);
    }

    public InstitutionDetail reactivate(Long id, String reason) {
        changeStatus(id, "ACTIVE", reason == null || reason.isBlank() ? null : reason.strip(), "INSTITUTION_REACTIVATED");
        return detail(id);
    }

    private void changeStatus(Long id, String status, String reason, String action) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenant(id);
            if (status.equals(t.getStatus())) {
                throw new BusinessRuleException("SAME_STATUS", "The institution is already " + status.toLowerCase(Locale.ROOT) + ".");
            }
            String before = t.getStatus();
            t.setStatus(status);
            t.setStatusReason(reason);
            t.setStatusChangedAt(OffsetDateTime.now());
            t.setStatusChangedBy(CurrentUser.platformName());
            tenants.saveAndFlush(t);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from", before);
            d.put("to", status);
            if (reason != null) d.put("reason", reason);
            platformAudit.record(action, t.getId(), "Tenant", t.getId(), d);
            work.within(t, () -> {
                audit.record(action, "Tenant", t.getId(), d);
                return null;
            });
        });
        lookup.evictAll();
    }

    // ------------------------------------------------------------------ administrators

    /** Adds another administrator to the institution, e.g. when its only administrator has left. */
    public IssuedAdmin addAdministrator(Long id, AdminRequest a) {
        String username = a.username().trim().toLowerCase(Locale.ROOT);
        String temporary = StaffUserService.temporaryPassword();
        Tenant t = tx.execute(s -> tenant(id));
        Long userId = work.call(t, () -> {
            UserAccount u = newAdmin(username, a, temporary);
            audit.record("CREATE", "StaffUser", u.getId(), Map.of("username", username, "roles", List.of(Role.INSTITUTION_ADMIN.name())));
            return u.getId();
        });
        tx.executeWithoutResult(s -> platformAudit.record("INSTITUTION_ADMIN_CREATED", t.getId(), "StaffUser", userId, Map.of("username", username)));
        InstitutionDetail detail = detail(id);
        return new IssuedAdmin(detail, username, temporary, detail.staffSignInUrl());
    }

    /**
     * Issues a temporary password to one of the institution's administrators (support, when they are locked out). Only
     * administrators: other staff are reset by the institution's own administrator.
     */
    public IssuedAdmin resetAdministratorPassword(Long id, Long userId) {
        String temporary = StaffUserService.temporaryPassword();
        Tenant t = tx.execute(s -> tenant(id));
        String username = work.call(t, () -> {
            UserAccount u = users.findById(userId).filter(x -> x.getUserType() == UserAccount.UserType.STAFF && x.hasRole(Role.INSTITUTION_ADMIN))
                    .orElseThrow(() -> new NotFoundException("Administrator not found in this institution"));
            u.setPasswordHash(encoder.encode(temporary));
            u.setMustChangePassword(true);
            u.setFailedLogins(0);
            if (u.getStatus() == UserAccount.Status.LOCKED) u.setStatus(UserAccount.Status.ACTIVE);
            u.setSessionsValidAfter(OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS));
            users.saveAndFlush(u);
            guard.evict(t.getId(), u.getId());
            audit.record("PASSWORD_RESET", "StaffUser", u.getId(), Map.of("username", u.getUsername()));
            return u.getUsername();
        });
        tx.executeWithoutResult(s -> platformAudit.record("INSTITUTION_ADMIN_PASSWORD_RESET", t.getId(), "StaffUser", userId, Map.of("username", username)));
        InstitutionDetail detail = detail(id);
        return new IssuedAdmin(detail, username, temporary, detail.staffSignInUrl());
    }

    // ------------------------------------------------------------------ SMS

    /** Credits an SMS bundle Msingi sold to the institution; it shows in the institution's messaging page as a top-up. */
    public InstitutionDetail creditSms(Long id, SmsCreditRequest req) {
        Tenant t = tx.execute(s -> tenant(id));
        work.run(t, () -> messaging.topup(new TopupRequest(req.units(), req.amountPaid(), req.reference())));
        tx.executeWithoutResult(s -> platformAudit.record("SMS_BUNDLE_CREDITED", t.getId(), "Tenant", t.getId(),
                Map.of("units", req.units(), "amount", req.amountPaid(), "reference", req.reference().trim())));
        return detail(id);
    }

    // ------------------------------------------------------------------ helpers

    private UserAccount newAdmin(String username, AdminRequest a, String temporary) {
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new BusinessRuleException("USERNAME_TAKEN", "The username " + username + " is already in use in this institution.");
        }
        String email = blank(a.email());
        if (email != null && users.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException("EMAIL_TAKEN", "Another account in this institution already uses " + email + ".");
        }
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setFullName(clean(a.fullName()));
        u.setEmail(email);
        u.setPhone(blank(a.phone()));
        u.setJobTitle("Institution Administrator");
        u.setUserType(UserAccount.UserType.STAFF);
        u.setStatus(UserAccount.Status.ACTIVE);
        u.setCreatedByName(CurrentUser.name());
        u.setPasswordHash(encoder.encode(temporary));
        u.setMustChangePassword(true);
        u.grant(Role.INSTITUTION_ADMIN);
        return users.saveAndFlush(u);
    }

    private TenantDomain addDomain(Tenant t, String host, String purpose) {
        TenantDomain d = new TenantDomain();
        d.setTenant(t);
        d.setHost(host);
        d.setPurpose(purpose);
        return domains.saveAndFlush(d);
    }

    private void requireFreeHost(String host) {
        if (domains.existsByHostIgnoreCase(host)) {
            throw new BusinessRuleException("DOMAIN_TAKEN", host + " is already the address of an institution.");
        }
    }

    /** "https://Apply.College.ac.tz/" → "apply.college.ac.tz"; anything else than a bare host name is refused. */
    static String normaliseHost(String input) {
        String h = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        h = h.replaceFirst("^https?://", "");
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (!HOST.matcher(h).matches() && !IPV4.matcher(h).matches()) {
            throw new BusinessRuleException("INVALID_DOMAIN", "'" + input.trim() + "' is not a host name. Enter it like apply.college.ac.tz, without a path or port.");
        }
        return h;
    }

    private Usage usage(Tenant t) {
        LocalDate jan1 = LocalDate.now(ZONE).withDayOfYear(1);
        OffsetDateTime yearStart = jan1.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime monthStart = LocalDate.now(ZONE).withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();
        return work.read(t, () -> {
            Map<String, Object> st = jdbc.queryForMap("select count(*) filter (where status = 'ACTIVE') as active, count(*) as total from students");
            Map<String, Object> staff = jdbc.queryForMap("select count(*) filter (where status = 'ACTIVE') as active, count(*) as total,"
                    + " max(last_login_at) as last from user_accounts where user_type = 'STAFF'");
            Long apps = jdbc.queryForObject("select count(*) from applications where submitted_at >= ?", Long.class, yearStart);
            Long programmes = jdbc.queryForObject("select count(*) from programmes where active", Long.class);
            Long parts = jdbc.queryForObject("select coalesce(sum(parts), 0) from messages where queued_at >= ?", Long.class, monthStart);
            Object last = staff.get("last");
            OffsetDateTime lastSignIn = last instanceof java.sql.Timestamp ts ? ts.toInstant().atZone(ZONE).toOffsetDateTime()
                    : last instanceof OffsetDateTime o ? o : null;
            return new Usage(num(st.get("active")), num(st.get("total")), apps == null ? 0 : apps, num(staff.get("active")), num(staff.get("total")),
                    programmes == null ? 0 : programmes, messaging.balance(), parts == null ? 0 : parts, lastSignIn);
        });
    }

    /** How far the institution has got in setting itself up after onboarding. */
    private List<SetupStep> setup(Tenant t, Usage u, boolean hasDomain) {
        return work.read(t, () -> {
            boolean adminSignedIn = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from user_accounts a join user_roles r on r.user_id = a.id"
                    + " where r.role = 'INSTITUTION_ADMIN' and a.last_login_at is not null and not a.must_change_password)", Boolean.class));
            boolean years = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from academic_years) and exists (select 1 from intakes)", Boolean.class));
            boolean fees = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from fee_structures)", Boolean.class));
            boolean window = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from admission_windows)", Boolean.class));
            boolean photo = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from institution_photos)", Boolean.class));
            return List.of(
                    new SetupStep("DOMAIN", "Web address", hasDomain, "Add the address its portals are reached at"),
                    new SetupStep("ADMIN", "Administrator signed in", adminSignedIn, "The administrator signs in and chooses their own password"),
                    new SetupStep("STAFF", "Staff accounts", u.staffTotal() > 1, "The administrator adds staff on Users & roles"),
                    new SetupStep("PROGRAMMES", "Programmes", u.programmes() > 0, "Departments and programmes with NACTVET codes"),
                    new SetupStep("CALENDAR", "Academic year and intake", years, "Set on the academic calendar"),
                    new SetupStep("FEES", "Fee structure", fees, "Fees per programme and year, for invoices and control numbers"),
                    new SetupStep("ADMISSION", "Admission window", window, "The first window applicants apply in"),
                    new SetupStep("BRANDING", "Campus photo", photo, "Shown on the portal home page"),
                    new SetupStep("SMS", "SMS bundle", u.smsBalance() > 0, "Credit an SMS bundle so applicants and students get messages"));
        });
    }

    private List<DomainView> domainsOf(Tenant t) {
        return domains.findAll().stream().filter(d -> d.getTenant().getId().equals(t.getId()))
                .sorted(Comparator.comparing(TenantDomain::getId))
                .map(d -> new DomainView(d.getId(), d.getHost(), d.getPurpose(), d.getCreatedAt())).toList();
    }

    private String signInUrl(List<DomainView> ds) {
        if (ds.isEmpty()) return null;
        return staffLoginUrl.replace("{host}", ds.get(0).host());
    }

    private Tenant tenant(Long id) {
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("Institution not found"));
    }

    private static AdminView adminView(UserAccount u) {
        return new AdminView(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getStatus().name(),
                u.isMustChangePassword(), u.getLastLoginAt());
    }

    private static String colour(Tenant t) {
        Object c = t.getBranding() == null ? null : t.getBranding().get("primaryColor");
        return c == null ? "#1F4E8C" : String.valueOf(c);
    }

    private static void note(Map<String, Object> changes, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) changes.put(field, after == null ? "" : after);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String clean(String s) {
        String v = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        if (!NO_CONTROL.matcher(v).matches()) throw new BusinessRuleException("INVALID_TEXT", "Remove special characters from '" + v + "'.");
        return v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : clean(s);
    }
}
