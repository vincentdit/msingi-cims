package tz.co.msingi.cims.hostel;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hostels the signed-in person works with (Document 03: a Hostel Warden's data scope is the assigned hostels). A warden assigned
 * to hostels sees and runs only those; a warden assigned to none (e.g. the Dean of Students) and the offices that see the whole
 * institution see every hostel. Window set-up, automatic allocation and releasing unpaid allocations affect every hostel, so they are
 * for those who see every hostel.
 */
@Component
@RequiredArgsConstructor
public class HostelScope {

    private final JdbcTemplate jdbc;
    private final ExamAccess access;

    /** The assigned hostels, or null for every hostel. */
    public Set<Long> hostels() {
        if (!access.has(Role.HOSTEL_WARDEN)) return null;
        if (access.has(Role.INSTITUTION_ADMIN, Role.REGISTRAR, Role.BURSAR, Role.ACCOUNTANT, Role.MANAGEMENT_VIEWER)) return null;
        Long me = CurrentUser.id().orElse(null);
        if (me == null) return null;
        List<Long> ids = jdbc.queryForList("select hostel_id from hostel_wardens where user_id = ?", Long.class, me);
        return ids.isEmpty() ? null : new HashSet<>(ids);
    }

    public boolean covers(Long hostelId) {
        Set<Long> s = hostels();
        return s == null || s.contains(hostelId);
    }

    public void require(Long hostelId) {
        if (!covers(hostelId)) throw new AccessDeniedException("That hostel is not one of yours");
    }

    public void requireAll(String what) {
        if (hostels() != null) throw new AccessDeniedException("Only a warden for every hostel can " + what);
    }
}
