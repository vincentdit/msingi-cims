package tz.co.msingi.cims.platform.users;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.SessionGuard;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccount.RoleAssignment;
import tz.co.msingi.cims.platform.security.UserAccount.Status;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.users.RoleCatalog.Scope;
import tz.co.msingi.cims.platform.users.StaffUserDtos.IssuedPassword;
import tz.co.msingi.cims.platform.users.StaffUserDtos.RoleGrant;
import tz.co.msingi.cims.platform.users.StaffUserDtos.RoleGrantView;
import tz.co.msingi.cims.platform.users.StaffUserDtos.StaffUserRequest;
import tz.co.msingi.cims.platform.users.StaffUserDtos.StaffUserView;
import tz.co.msingi.cims.platform.users.StaffUserDtos.Summary;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Staff accounts and their roles, managed by the Institution Administrator (as NOAS user management): create with a
 * temporary password, change details and roles, reset the password, disable and enable, unlock. Every change is audited,
 * and changes that take something away end the user's sessions at once.
 */
@Service
@RequiredArgsConstructor
public class StaffUserService {

    private static final String ALPHABET_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String ALPHABET_LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIAL = "@#$%&*!?";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final DepartmentRepository departments;
    private final ProgrammeRepository programmes;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SessionGuard guard;
    private final TokenService tokens;

    @Transactional(readOnly = true)
    public List<StaffUserView> list() {
        Names names = names();
        return users.findStaff().stream().map(u -> view(u, names)).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        List<UserAccount> all = users.findStaff();
        return new Summary(all.size(), count(all, Status.ACTIVE), count(all, Status.DISABLED), count(all, Status.LOCKED),
                all.stream().filter(UserAccount::isMustChangePassword).count());
    }

    @Transactional(readOnly = true)
    public StaffUserView get(Long id) {
        return view(staff(id), names());
    }

    @Transactional
    public IssuedPassword create(StaffUserRequest req) {
        String username = req.username().trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new BusinessRuleException("USERNAME_TAKEN", "The username " + username + " is already in use.");
        }
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setUserType(UserAccount.UserType.STAFF);
        u.setStatus(Status.ACTIVE);
        u.setCreatedByName(CurrentUser.name());
        details(u, req);
        u.setRoles(grants(req.roles()));
        String temporary = temporaryPassword();
        u.setPasswordHash(encoder.encode(temporary));
        u.setMustChangePassword(true);
        users.saveAndFlush(u);
        audit.record("CREATE", "StaffUser", u.getId(), Map.of("username", username, "roles", roleNames(u)));
        return new IssuedPassword(view(u, names()), temporary);
    }

    @Transactional
    public StaffUserView update(Long id, StaffUserRequest req) {
        UserAccount u = staff(id);
        if (!u.getUsername().equalsIgnoreCase(req.username().trim())) {
            throw new BusinessRuleException("USERNAME_FIXED", "A username cannot be changed. Create a new account instead.");
        }
        Set<RoleAssignment> before = new HashSet<>(u.getRoles());
        Set<RoleAssignment> after = grants(req.roles());
        if (isSelf(u) && hasAdmin(before) && !hasAdmin(after)) {
            throw new BusinessRuleException("OWN_ADMIN_ROLE", "You cannot remove your own administrator role. Ask another administrator.");
        }
        if (hasAdmin(before) && !hasAdmin(after)) {
            requireAnotherAdmin(u);
        }
        details(u, req);
        boolean rolesChanged = !sameGrants(before, after);
        u.getRoles().clear();
        u.getRoles().addAll(after);
        if (rolesChanged) {
            endSessions(u);
        }
        users.saveAndFlush(u);
        audit.record("UPDATE", "StaffUser", u.getId(), rolesChanged
                ? Map.of("roles", roleNames(u), "previousRoles", before.stream().map(r -> r.getRole().name()).sorted().toList())
                : Map.of());
        return view(u, names());
    }

    @Transactional
    public IssuedPassword resetPassword(Long id) {
        UserAccount u = staff(id);
        if (isSelf(u)) {
            throw new BusinessRuleException("OWN_PASSWORD", "Change your own password from your account, not here.");
        }
        String temporary = temporaryPassword();
        u.setPasswordHash(encoder.encode(temporary));
        u.setMustChangePassword(true);
        u.setFailedLogins(0);
        if (u.getStatus() == Status.LOCKED) {
            u.setStatus(Status.ACTIVE);
        }
        endSessions(u);
        users.saveAndFlush(u);
        audit.record("PASSWORD_RESET", "StaffUser", u.getId(), Map.of("username", u.getUsername()));
        return new IssuedPassword(view(u, names()), temporary);
    }

    @Transactional
    public StaffUserView disable(Long id, String reason) {
        UserAccount u = staff(id);
        if (isSelf(u)) {
            throw new BusinessRuleException("OWN_ACCOUNT", "You cannot disable your own account.");
        }
        if (u.getStatus() == Status.DISABLED) {
            return view(u, names());
        }
        if (hasAdmin(u.getRoles())) {
            requireAnotherAdmin(u);
        }
        u.setStatus(Status.DISABLED);
        endSessions(u);
        users.saveAndFlush(u);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("username", u.getUsername());
        if (reason != null && !reason.isBlank()) details.put("reason", reason.strip());
        audit.record("DISABLE", "StaffUser", u.getId(), details);
        return view(u, names());
    }

    @Transactional
    public StaffUserView enable(Long id) {
        UserAccount u = staff(id);
        if (u.getStatus() == Status.ACTIVE) {
            return view(u, names());
        }
        Status was = u.getStatus();
        u.setStatus(Status.ACTIVE);
        u.setFailedLogins(0);
        users.saveAndFlush(u);
        guard.evict(u.getTenantId(), u.getId());
        audit.record(was == Status.LOCKED ? "UNLOCK" : "ENABLE", "StaffUser", u.getId(), Map.of("username", u.getUsername()));
        return view(u, names());
    }

    /** The signed-in staff member replaces their password (a temporary one, or their own); their other sessions end. */
    @Transactional
    public TokenService.TokenResponse changeOwnPassword(String current, String next) {
        UserAccount u = users.findById(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("Account not found"));
        if (!encoder.matches(current, u.getPasswordHash())) {
            throw new BusinessRuleException("WRONG_PASSWORD", "Your current password is not correct.");
        }
        if (encoder.matches(next, u.getPasswordHash())) {
            throw new BusinessRuleException("SAME_PASSWORD", "Choose a password different from the current one.");
        }
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        endSessions(u);
        users.saveAndFlush(u);
        audit.record("PASSWORD_CHANGED", "UserAccount", u.getId(), null);
        return tokens.issue(u);
    }

    // ------------------------------------------------------------------ helpers

    private UserAccount staff(Long id) {
        return users.findById(id).filter(u -> u.getUserType() == UserAccount.UserType.STAFF)
                .orElseThrow(() -> new NotFoundException("Staff account not found"));
    }

    private void details(UserAccount u, StaffUserRequest req) {
        String email = blank(req.email());
        String phone = phone(req.phone());
        if (email != null && (u.getId() == null ? users.existsByEmailIgnoreCase(email) : users.existsByEmailIgnoreCaseAndIdNot(email, u.getId()))) {
            throw new BusinessRuleException("EMAIL_TAKEN", "Another account already uses " + email + ".");
        }
        if (phone != null && (u.getId() == null ? users.existsByPhone(phone) : users.existsByPhoneAndIdNot(phone, u.getId()))) {
            throw new BusinessRuleException("PHONE_TAKEN", "Another account already uses this phone number.");
        }
        u.setFullName(req.fullName().strip());
        u.setJobTitle(blank(req.jobTitle()));
        u.setEmail(email);
        u.setPhone(phone);
    }

    private Set<RoleAssignment> grants(List<RoleGrant> requested) {
        Set<RoleAssignment> out = new HashSet<>();
        Set<Role> seen = new HashSet<>();
        for (RoleGrant g : requested) {
            if (!RoleCatalog.isAssignable(g.role())) {
                throw new BusinessRuleException("ROLE_NOT_ASSIGNABLE", g.role() + " cannot be given to a staff account.");
            }
            if (!seen.add(g.role())) {
                throw new BusinessRuleException("ROLE_REPEATED", RoleCatalog.of(g.role()).label() + " is given more than once.");
            }
            Scope scope = g.scope() == null ? Scope.INSTITUTION : g.scope();
            if (!RoleCatalog.of(g.role()).scopes().contains(scope)) {
                throw new BusinessRuleException("SCOPE_NOT_ALLOWED", RoleCatalog.of(g.role()).label() + " cannot be limited to a " + scope.name().toLowerCase() + ".");
            }
            Long scopeId = null;
            if (scope == Scope.DEPARTMENT) {
                scopeId = departments.findById(req(g)).orElseThrow(() -> new BusinessRuleException("SCOPE_NOT_FOUND",
                        "Choose the department for " + RoleCatalog.of(g.role()).label() + ".")).getId();
            } else if (scope == Scope.PROGRAMME) {
                scopeId = programmes.findById(req(g)).orElseThrow(() -> new BusinessRuleException("SCOPE_NOT_FOUND",
                        "Choose the programme for " + RoleCatalog.of(g.role()).label() + ".")).getId();
            }
            out.add(new RoleAssignment(g.role(), scope.name(), scopeId));
        }
        return out;
    }

    private static Long req(RoleGrant g) {
        if (g.scopeId() == null) {
            throw new BusinessRuleException("SCOPE_NOT_FOUND", "Choose the " + (g.scope() == Scope.DEPARTMENT ? "department" : "programme")
                    + " for " + RoleCatalog.of(g.role()).label() + ".");
        }
        return g.scopeId();
    }

    private static boolean sameGrants(Set<RoleAssignment> a, Set<RoleAssignment> b) {
        if (a.size() != b.size()) return false;
        Comparator<RoleAssignment> c = Comparator.comparing((RoleAssignment r) -> r.getRole().name());
        List<RoleAssignment> x = a.stream().sorted(c).toList(), y = b.stream().sorted(c).toList();
        for (int i = 0; i < x.size(); i++) {
            RoleAssignment p = x.get(i), q = y.get(i);
            if (p.getRole() != q.getRole() || !p.getScopeType().equals(q.getScopeType()) || !Objects.equals(p.getScopeId(), q.getScopeId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAdmin(Set<RoleAssignment> roles) {
        return roles.stream().anyMatch(r -> r.getRole() == Role.INSTITUTION_ADMIN);
    }

    /** The institution always keeps at least one active administrator who can sign in. */
    private void requireAnotherAdmin(UserAccount u) {
        boolean another = users.findStaff().stream().anyMatch(o -> !o.getId().equals(u.getId()) && o.getStatus() == Status.ACTIVE && o.hasRole(Role.INSTITUTION_ADMIN));
        if (!another) {
            throw new BusinessRuleException("LAST_ADMIN", "This is the only active administrator. Make someone else an administrator first.");
        }
    }

    private void endSessions(UserAccount u) {
        u.setSessionsValidAfter(OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        guard.evict(TenantContext.requireTenantId(), u.getId());
    }

    private static boolean isSelf(UserAccount u) {
        return CurrentUser.id().map(u.getId()::equals).orElse(false);
    }

    private static long count(List<UserAccount> all, Status s) {
        return all.stream().filter(u -> u.getStatus() == s).count();
    }

    private static List<String> roleNames(UserAccount u) {
        return u.getRoles().stream().map(r -> r.getRole().name()).sorted().toList();
    }

    static String temporaryPassword() {
        List<Character> chars = new ArrayList<>();
        String all = ALPHABET_UPPER + ALPHABET_LOWER + DIGITS + SPECIAL;
        for (String set : List.of(ALPHABET_UPPER, ALPHABET_LOWER, DIGITS, SPECIAL)) chars.add(set.charAt(RANDOM.nextInt(set.length())));
        while (chars.size() < 12) chars.add(all.charAt(RANDOM.nextInt(all.length())));
        java.util.Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String phone(String s) {
        String p = blank(s);
        return p == null ? null : tz.co.msingi.cims.admission.service.PhoneNumbers.normalise(p);
    }

    private record Names(Map<Long, String> departments, Map<Long, String> programmes) {
    }

    private Names names() {
        Map<Long, String> d = new java.util.HashMap<>(), p = new java.util.HashMap<>();
        departments.findAll().forEach(x -> d.put(x.getId(), x.getName()));
        programmes.findAll().forEach(x -> p.put(x.getId(), x.getCode() + " · " + x.getName()));
        return new Names(d, p);
    }

    private StaffUserView view(UserAccount u, Names n) {
        List<RoleGrantView> roles = u.getRoles().stream()
                .sorted(Comparator.comparing(r -> r.getRole().ordinal()))
                .map(r -> {
                    Scope scope = Scope.valueOf(r.getScopeType());
                    String scopeName = scope == Scope.DEPARTMENT ? n.departments().get(r.getScopeId())
                            : scope == Scope.PROGRAMME ? n.programmes().get(r.getScopeId()) : null;
                    RoleCatalog.RoleInfo info = RoleCatalog.of(r.getRole());
                    return new RoleGrantView(r.getRole(), info == null ? r.getRole().name() : info.label(), scope, r.getScopeId(), scopeName);
                }).toList();
        return new StaffUserView(u.getId(), u.getUsername(), u.getFullName(), u.getJobTitle(), u.getEmail(), u.getPhone(), u.getStatus().name(),
                u.isMustChangePassword(), u.getLastLoginAt(), u.getCreatedAt(), u.getCreatedByName(), roles, isSelf(u));
    }
}
