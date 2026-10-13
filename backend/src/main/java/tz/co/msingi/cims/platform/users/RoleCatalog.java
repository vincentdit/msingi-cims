package tz.co.msingi.cims.platform.users;

import tz.co.msingi.cims.platform.security.Role;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What each staff role is for (Document 03, section 3.1) and the part of the institution it can be limited to.
 * Applicants and students get their accounts by applying and by admission, and the platform administrator belongs to
 * Msingi Technologies, so none of those are given by an institution administrator.
 */
public final class RoleCatalog {

    private RoleCatalog() {
    }

    public enum Scope { INSTITUTION, DEPARTMENT, PROGRAMME, SPONSOR }

    public record RoleInfo(Role role, String label, String description, List<Scope> scopes) {
    }

    private static final Map<Role, RoleInfo> ROLES = Map.ofEntries(
            info(Role.INSTITUTION_ADMIN, "Institution Administrator",
                    "Sets up the institution, its staff accounts and roles, the portal and integrations."),
            info(Role.MANAGEMENT_VIEWER, "Principal / Management",
                    "Sees dashboards and reports across the institution; changes nothing."),
            info(Role.DEPUTY_PRINCIPAL_ACADEMIC, "Deputy Principal (Academic)",
                    "Runs the academic calendar and approves for the Academic Committee: selection, curricula, results."),
            info(Role.ADMISSIONS_OFFICER, "Admissions Officer",
                    "Runs admission windows, applications, qualification checks, selection, NACTVET verification and letters."),
            info(Role.REGISTRAR, "Registrar / Registry Officer",
                    "Keeps student records: registration, status changes, semester registration and graduation lists."),
            info(Role.HEAD_OF_DEPARTMENT, "Head of Department",
                    "Allocates modules, moderates marks and approves for the department.", Scope.INSTITUTION, Scope.DEPARTMENT),
            info(Role.PROGRAMME_COORDINATOR, "Programme Coordinator",
                    "Prepares the programme's curriculum and looks after its classes.", Scope.INSTITUTION, Scope.PROGRAMME),
            info(Role.LECTURER, "Lecturer / Tutor",
                    "Takes attendance and enters coursework and examination marks for the modules allocated to them."),
            info(Role.ASSESSOR, "Assessor (NVA)",
                    "Records competence assessments for the NVA units allocated to them."),
            info(Role.EXAMINATIONS_OFFICER, "Examinations Officer",
                    "Runs marks windows, results processing and publication, resits and NACTVET results files."),
            info(Role.BURSAR, "Bursar / Finance Manager",
                    "Sets fee structures, approves waivers and refunds and signs off reconciliation."),
            info(Role.ACCOUNTANT, "Accountant / Finance Officer",
                    "Issues invoices, records payments and sponsors and handles payment exceptions."),
            info(Role.HOSTEL_WARDEN, "Dean of Students / Hostel Warden",
                    "Runs hostels: allocation, check-in and check-out."),
            info(Role.IPT_COORDINATOR, "IPT Coordinator",
                    "Arranges industrial practical training placements, supervisors and assessment.", Scope.INSTITUTION, Scope.DEPARTMENT),
            info(Role.CLEARANCE_OFFICER, "Clearance Officer",
                    "Clears or holds students for one clearance office, e.g. the library."),
            info(Role.SPONSOR_OFFICER, "Sponsor Officer",
                    "Someone from a sponsor (e.g. HESLB) who sees only that sponsor's students, invoices and statements.", Scope.SPONSOR),
            info(Role.AUDITOR, "Auditor",
                    "Reads records and the audit trail; changes nothing."));

    private static Map.Entry<Role, RoleInfo> info(Role role, String label, String description, Scope... scopes) {
        List<Scope> s = scopes.length == 0 ? List.of(Scope.INSTITUTION) : List.of(scopes);
        return Map.entry(role, new RoleInfo(role, label, description, s));
    }

    /** Roles an institution administrator can give, in the order they appear on screen. */
    public static List<RoleInfo> assignable() {
        return Arrays.stream(Role.values()).filter(ROLES::containsKey).map(ROLES::get).toList();
    }

    public static boolean isAssignable(Role role) {
        return ROLES.containsKey(role);
    }

    public static RoleInfo of(Role role) {
        return ROLES.get(role);
    }
}
