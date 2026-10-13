package tz.co.msingi.cims.platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.CurriculumModuleRepository;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Programme;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The part of the institution the signed-in person works in (Document 03: a Head of Department's data scope is the
 * department, a Programme Coordinator's the programme). It comes from their role assignments: anyone holding another
 * office role, or a Head of Department or Coordinator role for the whole institution, sees everything; otherwise they see
 * the programmes of their departments and their own programmes, and the modules taught in them or owned by their
 * departments. Lecturers, assessors and clearance officers are limited elsewhere (to their modules or office), so those
 * roles neither widen nor narrow what someone sees.
 */
@Component
@RequiredArgsConstructor
public class DataScope {

    private static final Set<Role> NEUTRAL = EnumSet.of(Role.LECTURER, Role.ASSESSOR, Role.CLEARANCE_OFFICER, Role.APPLICANT, Role.STUDENT);
    private static final String ATTRIBUTE = DataScope.class.getName();

    private final UserAccountRepository users;
    private final ProgrammeRepository programmes;
    private final CurriculumModuleRepository curriculumModules;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    /** What one person sees. {@code all} means the whole institution. */
    public static final class Scope {
        private final boolean all;
        private final Set<Long> departmentIds;
        private final Set<Long> programmeIds;
        private final Function<Collection<Long>, List<Long>> moduleLookup;
        private Set<Long> moduleIds;

        Scope(boolean all, Set<Long> departmentIds, Set<Long> programmeIds, Function<Collection<Long>, List<Long>> moduleLookup) {
            this.all = all;
            this.departmentIds = Set.copyOf(departmentIds);
            this.programmeIds = Set.copyOf(programmeIds);
            this.moduleLookup = moduleLookup;
        }

        public boolean all() {
            return all;
        }

        /** The programmes this person sees (only meaningful when not {@link #all()}). */
        public Set<Long> programmeIds() {
            return programmeIds;
        }

        public Set<Long> departmentIds() {
            return departmentIds;
        }

        public boolean programme(Long programmeId) {
            return all || (programmeId != null && programmeIds.contains(programmeId));
        }

        public boolean programme(Programme p) {
            return p != null && programme(p.getId());
        }

        public boolean department(Long departmentId) {
            return all || (departmentId != null && departmentIds.contains(departmentId));
        }

        /** A module owned by one of their departments, or taught in one of their programmes. */
        public boolean module(Module m) {
            if (all) return true;
            if (m == null) return false;
            if (m.getDepartment() != null && departmentIds.contains(m.getDepartment().getId())) return true;
            if (moduleIds == null) moduleIds = programmeIds.isEmpty() ? Set.of() : new HashSet<>(moduleLookup.apply(programmeIds));
            return moduleIds.contains(m.getId());
        }

        /** A short description for messages, e.g. "your department". */
        public String describe() {
            return !departmentIds.isEmpty() ? "your department" : "your programme";
        }
    }

    /** The signed-in person's scope (worked out once per request). */
    public Scope current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Scope cached) {
            return cached;
        }
        Scope s = tx.execute(st -> compute()); // queries need the institution set on the connection (row-level security)
        if (attrs != null) attrs.setAttribute(ATTRIBUTE, s, RequestAttributes.SCOPE_REQUEST);
        return s;
    }

    public void requireProgramme(Long programmeId) {
        Scope s = current();
        if (!s.programme(programmeId)) throw new AccessDeniedException("That programme is outside " + s.describe());
    }

    public void requireModule(Module m) {
        Scope s = current();
        if (!s.module(m)) throw new AccessDeniedException("That module is outside " + s.describe());
    }

    private Scope compute() {
        Long id = CurrentUser.id().orElse(null);
        UserAccount u = id == null ? null : users.findById(id).orElse(null);
        if (u == null || u.getUserType() != UserAccount.UserType.STAFF) return everything();
        Set<Long> departments = new HashSet<>(), progs = new HashSet<>();
        boolean scoped = false;
        for (UserAccount.RoleAssignment r : u.getRoles()) {
            if (NEUTRAL.contains(r.getRole())) continue;
            if (r.getRole() == Role.HEAD_OF_DEPARTMENT && "DEPARTMENT".equals(r.getScopeType()) && r.getScopeId() != null) {
                departments.add(r.getScopeId());
                scoped = true;
            } else if (r.getRole() == Role.PROGRAMME_COORDINATOR && "PROGRAMME".equals(r.getScopeType()) && r.getScopeId() != null) {
                progs.add(r.getScopeId());
                scoped = true;
            } else {
                return everything(); // an office role for the whole institution
            }
        }
        if (!scoped) return everything();
        if (!departments.isEmpty()) progs.addAll(programmes.idsInDepartments(departments));
        return new Scope(false, departments, progs, ids -> tx.execute(st -> curriculumModules.moduleIdsOfProgrammes(ids)));
    }

    private Scope everything() {
        return new Scope(true, Set.of(), Set.of(), ids -> List.of());
    }
}
