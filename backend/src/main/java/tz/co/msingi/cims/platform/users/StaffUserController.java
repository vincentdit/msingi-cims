package tz.co.msingi.cims.platform.users;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.users.StaffUserDtos.ChangePasswordRequest;
import tz.co.msingi.cims.platform.users.StaffUserDtos.IssuedPassword;
import tz.co.msingi.cims.platform.users.StaffUserDtos.StaffUserRequest;
import tz.co.msingi.cims.platform.users.StaffUserDtos.StaffUserView;
import tz.co.msingi.cims.platform.users.StaffUserDtos.StatusRequest;
import tz.co.msingi.cims.platform.users.StaffUserDtos.Summary;

import java.util.List;

/** Staff accounts and roles (Institution Administrator; the Auditor may look), and each staff member's own password. */
@Tag(name = "Staff users and roles")
@RestController
@RequiredArgsConstructor
public class StaffUserController {

    private static final String ADMIN = "hasRole('INSTITUTION_ADMIN')";
    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','AUDITOR')";

    private final StaffUserService service;
    private final tz.co.msingi.cims.platform.security.DataScope scope;
    private final tz.co.msingi.cims.academic.domain.AcademicRepositories.DepartmentRepository departments;
    private final tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository programmes;

    @Operation(summary = "Roles that can be given to staff, with what each is for and how it can be limited")
    @GetMapping("/api/admin/roles")
    @PreAuthorize(READ)
    public List<RoleCatalog.RoleInfo> roles() {
        return RoleCatalog.assignable();
    }

    @GetMapping("/api/admin/users")
    @PreAuthorize(READ)
    public List<StaffUserView> list() {
        return service.list();
    }

    @GetMapping("/api/admin/users/summary")
    @PreAuthorize(READ)
    public Summary summary() {
        return service.summary();
    }

    @GetMapping("/api/admin/users/{id}")
    @PreAuthorize(READ)
    public StaffUserView get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Create a staff account; returns a temporary password to hand to the user")
    @PostMapping("/api/admin/users")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedPassword create(@Valid @RequestBody StaffUserRequest req) {
        return service.create(req);
    }

    @Operation(summary = "Change a staff member's details and roles (changed roles sign them out)")
    @PutMapping("/api/admin/users/{id}")
    @PreAuthorize(ADMIN)
    public StaffUserView update(@PathVariable Long id, @Valid @RequestBody StaffUserRequest req) {
        return service.update(id, req);
    }

    @Operation(summary = "Issue a new temporary password (signs the user out everywhere)")
    @PostMapping("/api/admin/users/{id}/reset-password")
    @PreAuthorize(ADMIN)
    public IssuedPassword resetPassword(@PathVariable Long id) {
        return service.resetPassword(id);
    }

    @PostMapping("/api/admin/users/{id}/disable")
    @PreAuthorize(ADMIN)
    public StaffUserView disable(@PathVariable Long id, @Valid @RequestBody(required = false) StatusRequest req) {
        return service.disable(id, req == null ? null : req.reason());
    }

    @Operation(summary = "Enable a disabled account or unlock a locked one")
    @PostMapping("/api/admin/users/{id}/enable")
    @PreAuthorize(ADMIN)
    public StaffUserView enable(@PathVariable Long id) {
        return service.enable(id);
    }

    public record ScopeView(boolean all, List<String> departments, List<String> programmes) {
    }

    @Operation(summary = "The part of the institution the signed-in person works in (department or programme limits)")
    @GetMapping("/api/auth/scope")
    @PreAuthorize("isAuthenticated()")
    public ScopeView scope() {
        var s = scope.current();
        if (s.all()) return new ScopeView(true, List.of(), List.of());
        return new ScopeView(false, departments.findAllById(s.departmentIds()).stream().map(d -> d.getName()).sorted().toList(),
                programmes.findAllById(s.programmeIds()).stream().map(p -> p.getCode() + " · " + p.getName()).sorted().toList());
    }

    @Operation(summary = "Change your own password (staff); returns a fresh session")
    @PostMapping("/api/auth/password")
    @PreAuthorize("isAuthenticated()")
    public TokenService.TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return service.changeOwnPassword(req.currentPassword(), req.newPassword());
    }
}
