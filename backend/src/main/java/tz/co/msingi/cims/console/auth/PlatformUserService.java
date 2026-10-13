package tz.co.msingi.cims.console.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.IssuedPassword;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.PlatformUserRequest;
import tz.co.msingi.cims.console.auth.PlatformAuthDtos.PlatformUserView;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.SessionGuard;
import tz.co.msingi.cims.platform.users.StaffUserService;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Msingi platform staff accounts: added with a temporary password, reset, disabled and enabled by another platform administrator. */
@Service
@RequiredArgsConstructor
public class PlatformUserService {

    private final PlatformUserRepository users;
    private final PasswordEncoder encoder;
    private final PlatformAuditService audit;
    private final SessionGuard guard;

    @Transactional(readOnly = true)
    public List<PlatformUserView> list() {
        return users.findAllByOrderByFullNameAsc().stream().map(PlatformUserService::view).toList();
    }

    @Transactional
    public IssuedPassword create(PlatformUserRequest req) {
        String username = req.username().trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new BusinessRuleException("USERNAME_TAKEN", "The username " + username + " is already in use.");
        }
        PlatformUser u = new PlatformUser();
        u.setUsername(username);
        u.setFullName(req.fullName().strip());
        u.setEmail(blank(req.email()));
        u.setPhone(blank(req.phone()));
        u.setCreatedByName(CurrentUser.platformName());
        String temporary = StaffUserService.temporaryPassword();
        u.setPasswordHash(encoder.encode(temporary));
        u.setMustChangePassword(true);
        users.saveAndFlush(u);
        audit.record("PLATFORM_USER_CREATED", null, "PlatformUser", u.getId(), Map.of("username", username));
        return new IssuedPassword(view(u), temporary);
    }

    @Transactional
    public IssuedPassword resetPassword(Long id) {
        PlatformUser u = user(id);
        if (isSelf(u)) throw new BusinessRuleException("OWN_PASSWORD", "Change your own password from your account, not here.");
        String temporary = StaffUserService.temporaryPassword();
        u.setPasswordHash(encoder.encode(temporary));
        u.setMustChangePassword(true);
        u.setFailedLogins(0);
        if (u.getStatus() == PlatformUser.Status.LOCKED) u.setStatus(PlatformUser.Status.ACTIVE);
        endSessions(u);
        audit.record("PLATFORM_USER_PASSWORD_RESET", null, "PlatformUser", u.getId(), Map.of("username", u.getUsername()));
        return new IssuedPassword(view(u), temporary);
    }

    @Transactional
    public PlatformUserView disable(Long id) {
        PlatformUser u = user(id);
        if (isSelf(u)) throw new BusinessRuleException("OWN_ACCOUNT", "You cannot disable your own account.");
        boolean another = users.findAll().stream().anyMatch(o -> !o.getId().equals(u.getId()) && o.getStatus() == PlatformUser.Status.ACTIVE);
        if (!another) throw new BusinessRuleException("LAST_ADMIN", "This is the only active platform administrator.");
        u.setStatus(PlatformUser.Status.DISABLED);
        endSessions(u);
        audit.record("PLATFORM_USER_DISABLED", null, "PlatformUser", u.getId(), Map.of("username", u.getUsername()));
        return view(u);
    }

    @Transactional
    public PlatformUserView enable(Long id) {
        PlatformUser u = user(id);
        u.setStatus(PlatformUser.Status.ACTIVE);
        u.setFailedLogins(0);
        guard.evictPlatform(u.getId());
        audit.record("PLATFORM_USER_ENABLED", null, "PlatformUser", u.getId(), Map.of("username", u.getUsername()));
        return view(u);
    }

    private void endSessions(PlatformUser u) {
        u.setSessionsValidAfter(OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        users.saveAndFlush(u);
        guard.evictPlatform(u.getId());
    }

    private PlatformUser user(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("Platform account not found"));
    }

    private static boolean isSelf(PlatformUser u) {
        return Objects.equals(CurrentUser.platformId().orElse(null), u.getId());
    }

    static PlatformUserView view(PlatformUser u) {
        return new PlatformUserView(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(), u.getStatus().name(),
                u.isMustChangePassword(), u.getLastLoginAt(), u.getCreatedByName(), u.getCreatedAt(), isSelf(u));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
