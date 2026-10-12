package tz.co.msingi.cims.exams;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may do what in the results workflow. Roles come from the token; a Head of Department whose role assignment is scoped to a
 * department (scope DEPARTMENT) moderates only that department's modules.
 */
@Component
@RequiredArgsConstructor
public class ExamAccess {

    public static final Role[] EXAMS_OFFICE = {Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER};
    public static final Role[] COMMITTEE = {Role.INSTITUTION_ADMIN, Role.DEPUTY_PRINCIPAL_ACADEMIC};
    public static final Role[] BOARD = {Role.INSTITUTION_ADMIN};
    public static final Role[] ALLOCATORS = {Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.HEAD_OF_DEPARTMENT, Role.DEPUTY_PRINCIPAL_ACADEMIC};

    private final UserAccountRepository users;

    public Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    public boolean has(Role... any) {
        Set<String> mine = roles();
        return Arrays.stream(any).anyMatch(r -> mine.contains(r.authority()));
    }

    public void require(String what, Role... any) {
        if (!has(any)) throw new AccessDeniedException("Not allowed to " + what);
    }

    public Long me() {
        return CurrentUser.requireId();
    }

    /** Whether the signed-in user is Head of this department (department-scoped or institution-wide). */
    public boolean headsDepartment(Long departmentId) {
        if (!has(Role.HEAD_OF_DEPARTMENT)) return false;
        UserAccount u = users.findById(me()).orElse(null);
        if (u == null) return false;
        return u.getRoles().stream().filter(r -> r.getRole() == Role.HEAD_OF_DEPARTMENT)
                .anyMatch(r -> !"DEPARTMENT".equals(r.getScopeType()) || Objects.equals(r.getScopeId(), departmentId));
    }

    /** Whether the signed-in Head of Department moderates this module (department-scoped or institution-wide). */
    public boolean moderates(Module module) {
        if (!has(Role.HEAD_OF_DEPARTMENT)) return false;
        UserAccount u = users.findById(me()).orElse(null);
        if (u == null) return false;
        return u.getRoles().stream().filter(r -> r.getRole() == Role.HEAD_OF_DEPARTMENT).anyMatch(r -> !"DEPARTMENT".equals(r.getScopeType())
                || (module.getDepartment() != null && Objects.equals(r.getScopeId(), module.getDepartment().getId())));
    }
}
