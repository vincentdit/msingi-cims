package tz.co.msingi.cims.hr;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Leave (FR-HR-03): leave types with their yearly days, public holidays, requests from staff (or recorded by HR for someone without a
 * CIMS account), approval by the Head of the person's department or by HR, and balances per year. Working days leave out weekends and
 * public holidays: the fixed national days below plus the dates HR adds each year (Good Friday, Easter Monday, Eid, Maulid). A type
 * counted in calendar days (maternity, annual leave as the law gives it) counts every day.
 */
@Service
@RequiredArgsConstructor
public class LeaveService {

    /** The public holidays on fixed dates in Tanzania. */
    static final Map<MonthDay, String> FIXED = Map.ofEntries(
            Map.entry(MonthDay.of(1, 1), "New Year's Day"), Map.entry(MonthDay.of(1, 12), "Zanzibar Revolution Day"),
            Map.entry(MonthDay.of(4, 7), "Karume Day"), Map.entry(MonthDay.of(4, 26), "Union Day"), Map.entry(MonthDay.of(5, 1), "Workers' Day"),
            Map.entry(MonthDay.of(7, 7), "Saba Saba"), Map.entry(MonthDay.of(8, 8), "Nane Nane"), Map.entry(MonthDay.of(10, 14), "Nyerere Day"),
            Map.entry(MonthDay.of(12, 9), "Independence Day"), Map.entry(MonthDay.of(12, 25), "Christmas Day"), Map.entry(MonthDay.of(12, 26), "Boxing Day"));
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Role[] HR = {Role.INSTITUTION_ADMIN, Role.HR_OFFICER};

    private final JdbcTemplate jdbc;
    private final StaffService staff;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    public record LeaveType(Long id, String code, String name, Integer daysPerYear, String onlyGender, boolean calendarDays, boolean active) {
    }

    public record Holiday(Long id, LocalDate day, String name, boolean fixed) {
    }

    public record Balance(Long leaveTypeId, String code, String name, Integer entitlement, int adjustments, int taken, int pending, Integer remaining) {
    }

    public record RequestView(Long id, Long staffId, String staffName, String staffNo, String department, Long leaveTypeId, String leaveType,
                              LocalDate startOn, LocalDate endOn, int days, String reason, String reliefName, String status, String submittedByName,
                              OffsetDateTime submittedAt, String decidedByName, OffsetDateTime decidedAt, String decisionNote, boolean canDecide) {
    }

    public record NewRequest(Long leaveTypeId, LocalDate startOn, LocalDate endOn, String reason, String reliefName) {
    }

    public record MyLeave(Long staffId, String name, int year, List<Balance> balances, List<RequestView> requests, List<LeaveType> types) {
    }

    public record DaysPreview(int days, List<String> skipped) {
    }

    // ------------------------------------------------------------------ leave types and holidays

    @Transactional
    public List<LeaveType> types() {
        seed();
        return jdbc.query("select id, code, name, days_per_year, only_gender, calendar_days, active from leave_types order by sort_order, name",
                (rs, n) -> new LeaveType(rs.getLong(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), rs.getString(5), rs.getBoolean(6),
                        rs.getBoolean(7)));
    }

    @Transactional
    public List<LeaveType> saveType(Long id, LeaveType t) {
        access.require("change leave types", HR);
        String name = t.name() == null || t.name().isBlank() ? null : t.name().trim();
        if (name == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give the name of the leave");
        if (t.daysPerYear() != null && (t.daysPerYear() < 0 || t.daysPerYear() > 366)) throw new BusinessRuleException("DAYS_INVALID", "Check the days per year");
        String g = t.onlyGender() == null || t.onlyGender().isBlank() ? null : t.onlyGender().toUpperCase(Locale.ROOT);
        if (g != null && !Set.of("M", "F").contains(g)) throw new BusinessRuleException("GENDER_INVALID", "Male, female or anyone");
        if (id == null) {
            String code = t.code() == null || t.code().isBlank() ? name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") : t.code().trim().toUpperCase(Locale.ROOT);
            if (code.length() > 20) code = code.substring(0, 20);
            Integer n = jdbc.queryForObject("select count(*) from leave_types where code = ?", Integer.class, code);
            if (n != null && n > 0) throw new BusinessRuleException("CODE_TAKEN", "There is already a leave type " + code);
            id = jdbc.queryForObject("""
                    insert into leave_types (tenant_id, code, name, days_per_year, only_gender, calendar_days, active, sort_order)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, 90) returning id""", Long.class, code, name, t.daysPerYear(), g, t.calendarDays(), t.active());
        } else if (jdbc.update("update leave_types set name = ?, days_per_year = ?, only_gender = ?, calendar_days = ?, active = ? where id = ?",
                name, t.daysPerYear(), g, t.calendarDays(), t.active(), id) == 0) {
            throw new NotFoundException("Leave type not found");
        }
        audit.record("LEAVE_TYPE_SAVED", "LeaveType", id, Map.of("name", name));
        return types();
    }

    @Transactional(readOnly = true)
    public List<Holiday> holidays(int year) {
        List<Holiday> out = new ArrayList<>();
        FIXED.forEach((md, name) -> out.add(new Holiday(null, md.atYear(year), name, true)));
        out.addAll(jdbc.query("select id, day, name from public_holidays where extract(year from day) = ? order by day",
                (rs, n) -> new Holiday(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), false), year));
        out.sort(java.util.Comparator.comparing(Holiday::day));
        return out;
    }

    @Transactional
    public List<Holiday> addHoliday(LocalDate day, String name) {
        access.require("add public holidays", HR);
        if (day == null || name == null || name.isBlank()) throw new BusinessRuleException("FIELD_REQUIRED", "Give the date and the name of the holiday");
        if (FIXED.containsKey(MonthDay.from(day))) throw new BusinessRuleException("HOLIDAY_FIXED", FIXED.get(MonthDay.from(day)) + " is already a holiday");
        jdbc.update("insert into public_holidays (tenant_id, day, name) values (cims_current_tenant(), ?, ?) on conflict (tenant_id, day) do update set name = excluded.name",
                day, name.trim().length() > 80 ? name.trim().substring(0, 80) : name.trim());
        audit.record("PUBLIC_HOLIDAY_ADDED", "PublicHoliday", null, Map.of("day", day.toString(), "name", name.trim()));
        return holidays(day.getYear());
    }

    @Transactional
    public List<Holiday> removeHoliday(Long id) {
        access.require("remove public holidays", HR);
        LocalDate day = jdbc.query("select day from public_holidays where id = ?", (rs, n) -> rs.getObject(1, LocalDate.class), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Holiday not found"));
        jdbc.update("delete from public_holidays where id = ?", id);
        audit.record("PUBLIC_HOLIDAY_REMOVED", "PublicHoliday", id, Map.of("day", day.toString()));
        return holidays(day.getYear());
    }

    /** How many days a period counts as, and which days were left out. */
    @Transactional(readOnly = true)
    public DaysPreview count(Long leaveTypeId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw new BusinessRuleException("DATES_INVALID", "The leave ends before it starts");
        if (from.plusDays(400).isBefore(to)) throw new BusinessRuleException("DATES_INVALID", "Split leave longer than a year");
        boolean calendar = leaveTypeId != null && Boolean.TRUE.equals(jdbc.query("select calendar_days from leave_types where id = ?",
                (rs, n) -> rs.getBoolean(1), leaveTypeId).stream().findFirst().orElse(false));
        Set<LocalDate> extra = new HashSet<>(jdbc.query("select day from public_holidays where day between ? and ?", (rs, n) -> rs.getObject(1, LocalDate.class), from, to));
        int days = 0;
        List<String> skipped = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (calendar) {
                days++;
                continue;
            }
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (FIXED.containsKey(MonthDay.from(d)) || extra.contains(d)) {
                skipped.add(DAY.format(d) + " (" + (FIXED.containsKey(MonthDay.from(d)) ? FIXED.get(MonthDay.from(d)) : "public holiday") + ")");
                continue;
            }
            days++;
        }
        return new DaysPreview(days, skipped);
    }

    // ------------------------------------------------------------------ requests

    @Transactional
    public MyLeave mine(Integer year) {
        Long id = staff.myStaffId();
        int y = year == null ? LocalDate.now(TZ).getYear() : year;
        Map<String, Object> m = jdbc.queryForMap("select full_name, gender from staff_members where id = ?", id);
        String gender = (String) m.get("gender");
        return new MyLeave(id, (String) m.get("full_name"), y, balances(id, y), requests(" where r.staff_id = ? order by r.start_on desc limit 50", id),
                types().stream().filter(t -> t.active() && (t.onlyGender() == null || t.onlyGender().equals(gender))).toList());
    }

    @Transactional
    public RequestView apply(NewRequest r) {
        Long me = staff.myStaffId();
        return create(me, r, false);
    }

    /** HR records leave for someone (also for staff without a CIMS account); it is approved at once. */
    @Transactional
    public RequestView record(Long staffId, NewRequest r) {
        access.require("record leave", HR);
        return create(staffId, r, true);
    }

    private RequestView create(Long staffId, NewRequest r, boolean approved) {
        types();
        Map<String, Object> s = jdbc.queryForList("select full_name, gender, status, phone from staff_members where id = ?", staffId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
        if (!"ACTIVE".equals(s.get("status"))) throw new BusinessRuleException("STAFF_LEFT", "This person no longer works here");
        LeaveType t = types().stream().filter(x -> x.id().equals(r.leaveTypeId()) && x.active()).findFirst()
                .orElseThrow(() -> new BusinessRuleException("LEAVE_TYPE", "Choose the kind of leave"));
        if (t.onlyGender() != null && !t.onlyGender().equals(s.get("gender"))) throw new BusinessRuleException("LEAVE_TYPE", t.name() + " is not open to this person");
        DaysPreview p = count(t.id(), r.startOn(), r.endOn());
        if (p.days() == 0) throw new BusinessRuleException("NO_WORKING_DAYS", "The dates are all weekends or public holidays");
        if (!approved && r.startOn().isBefore(LocalDate.now(TZ).minusDays(30))) {
            throw new BusinessRuleException("TOO_LATE", "Leave that started more than 30 days ago is recorded by Human Resources");
        }
        Integer overlap = jdbc.queryForObject("""
                select count(*) from leave_requests where staff_id = ? and status in ('SUBMITTED', 'APPROVED') and start_on <= ? and end_on >= ?""",
                Integer.class, staffId, r.endOn(), r.startOn());
        if (overlap != null && overlap > 0) throw new BusinessRuleException("LEAVE_OVERLAPS", "These dates overlap leave already requested or approved");
        if (t.daysPerYear() != null) {
            Balance b = balances(staffId, r.startOn().getYear()).stream().filter(x -> x.leaveTypeId().equals(t.id())).findFirst().orElseThrow();
            int left = b.remaining() - b.pending();
            if (p.days() > left) {
                throw new BusinessRuleException("NOT_ENOUGH_DAYS", "Only " + Math.max(left, 0) + " days of " + t.name() + " are left for " + r.startOn().getYear()
                        + (b.pending() > 0 ? " (with " + b.pending() + " days waiting for approval)" : ""));
            }
        }
        Long id = jdbc.queryForObject("""
                insert into leave_requests (tenant_id, staff_id, leave_type_id, start_on, end_on, days, reason, relief_name, status, submitted_by_name,
                                            decided_by_name, decided_at)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, staffId, t.id(), r.startOn(), r.endOn(), p.days(),
                cut(r.reason(), 500), cut(r.reliefName(), 150), approved ? "APPROVED" : "SUBMITTED", CurrentUser.name(),
                approved ? CurrentUser.name() : null, approved ? Timestamp.from(java.time.Instant.now()) : null);
        audit.record(approved ? "LEAVE_RECORDED" : "LEAVE_REQUESTED", "LeaveRequest", id, Map.of("staffId", staffId, "type", t.code(), "days", p.days()));
        return request(id);
    }

    @Transactional
    public RequestView cancel(Long id) {
        RequestView r = request(id);
        Long me = jdbc.query("select id from staff_members where user_id = ?", (rs, n) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst().orElse(null);
        boolean own = r.staffId().equals(me);
        if (!own && !access.has(HR)) throw new AccessDeniedException("Only the person or Human Resources can cancel leave");
        if (!Set.of("SUBMITTED", "APPROVED").contains(r.status())) throw new BusinessRuleException("LEAVE_CLOSED", "This request is already " + r.status().toLowerCase(Locale.ROOT));
        if (own && !access.has(HR) && "APPROVED".equals(r.status()) && !r.startOn().isAfter(LocalDate.now(TZ))) {
            throw new BusinessRuleException("LEAVE_STARTED", "The leave has started; ask Human Resources to change it");
        }
        jdbc.update("update leave_requests set status = 'CANCELLED', decision_note = ? where id = ?", "Cancelled by " + CurrentUser.name(), id);
        audit.record("LEAVE_CANCELLED", "LeaveRequest", id, Map.of());
        return request(id);
    }

    /** Requests the signed-in person may see: HR sees everyone; a Head of Department the staff of their departments. */
    @Transactional(readOnly = true)
    public List<RequestView> list(String status, Integer year) {
        boolean hr = access.has(HR) || access.has(Role.MANAGEMENT_VIEWER, Role.AUDITOR);
        if (!hr && !access.has(Role.HEAD_OF_DEPARTMENT)) throw new AccessDeniedException("Not allowed to see leave requests");
        String st = status == null || status.isBlank() ? "SUBMITTED" : status.toUpperCase(Locale.ROOT);
        int y = year == null ? LocalDate.now(TZ).getYear() : year;
        List<RequestView> all = "SUBMITTED".equals(st)
                ? requests(" where r.status = 'SUBMITTED' order by r.start_on")
                : "ALL".equals(st)
                ? requests(" where extract(year from r.start_on) = ? order by r.start_on desc", y)
                : requests(" where r.status = ? and extract(year from r.start_on) = ? order by r.start_on desc", st, y);
        return hr ? all : all.stream().filter(RequestView::canDecide).toList();
    }

    /** Who is away on a day (for the Head of Department and HR). */
    @Transactional(readOnly = true)
    public List<RequestView> away(LocalDate day) {
        LocalDate d = day == null ? LocalDate.now(TZ) : day;
        boolean hr = access.has(HR) || access.has(Role.MANAGEMENT_VIEWER, Role.AUDITOR);
        List<RequestView> all = requests(" where r.status = 'APPROVED' and r.start_on <= ? and r.end_on >= ? order by m.full_name", d, d);
        return hr ? all : all.stream().filter(r -> headsDepartmentOf(r.staffId())).toList();
    }

    @Transactional
    public RequestView decide(Long id, boolean approve, String note) {
        RequestView r = request(id);
        if (!r.canDecide()) throw new AccessDeniedException("Only the Head of the person's department or Human Resources decides this request");
        if (!"SUBMITTED".equals(r.status())) throw new BusinessRuleException("LEAVE_DECIDED", "This request is already " + r.status().toLowerCase(Locale.ROOT));
        String n = cut(note, 500);
        if (!approve && n == null) throw new BusinessRuleException("REASON_REQUIRED", "Say why the leave is not approved");
        if (approve) {
            // the balance may have changed since the request was made
            LeaveType t = types().stream().filter(x -> x.id().equals(r.leaveTypeId())).findFirst().orElseThrow();
            if (t.daysPerYear() != null) {
                Balance b = balances(r.staffId(), r.startOn().getYear()).stream().filter(x -> x.leaveTypeId().equals(t.id())).findFirst().orElseThrow();
                if (r.days() > b.remaining()) throw new BusinessRuleException("NOT_ENOUGH_DAYS", "Only " + Math.max(b.remaining(), 0) + " days of " + t.name() + " are left");
            }
        }
        jdbc.update("update leave_requests set status = ?, decided_by_name = ?, decided_at = now(), decision_note = ? where id = ?",
                approve ? "APPROVED" : "REJECTED", CurrentUser.name(), n, id);
        audit.record(approve ? "LEAVE_APPROVED" : "LEAVE_NOT_APPROVED", "LeaveRequest", id, Map.of("staffId", r.staffId(), "days", r.days()));
        String phone = jdbc.queryForObject("select coalesce(m.phone, u.phone) from staff_members m left join user_accounts u on u.id = m.user_id where m.id = ?",
                String.class, r.staffId());
        messaging.event(approve ? "LEAVE_APPROVED" : "LEAVE_NOT_APPROVED", phone, Map.of("leave", r.leaveType(), "from", DAY.format(r.startOn()),
                "to", DAY.format(r.endOn()), "days", String.valueOf(r.days()), "note", n == null ? "" : n));
        return request(id);
    }

    /** Days carried forward or an opening balance, by HR. */
    @Transactional
    public List<Balance> adjust(Long staffId, Long leaveTypeId, int year, int days, String reason) {
        access.require("adjust leave balances", HR);
        if (days == 0 || Math.abs(days) > 366) throw new BusinessRuleException("DAYS_INVALID", "Give the days to add (or, negative, to take away)");
        if (reason == null || reason.isBlank()) throw new BusinessRuleException("REASON_REQUIRED", "Say why the balance changes");
        jdbc.update("""
                insert into leave_adjustments (tenant_id, staff_id, leave_type_id, year, days, reason, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?)""", staffId, leaveTypeId, year, days, cut(reason, 300), CurrentUser.name());
        audit.record("LEAVE_BALANCE_ADJUSTED", "StaffMember", staffId, Map.of("leaveTypeId", leaveTypeId, "year", year, "days", days));
        return balances(staffId, year);
    }

    @Transactional(readOnly = true)
    public List<Balance> balancesFor(Long staffId, int year) {
        if (!access.has(HR) && !access.has(Role.MANAGEMENT_VIEWER, Role.AUDITOR) && !headsDepartmentOf(staffId)) {
            throw new AccessDeniedException("Not allowed to see this person's leave");
        }
        return balances(staffId, year);
    }

    @Transactional(readOnly = true)
    public List<RequestView> historyFor(Long staffId) {
        if (!access.has(HR) && !access.has(Role.MANAGEMENT_VIEWER, Role.AUDITOR) && !headsDepartmentOf(staffId)) {
            throw new AccessDeniedException("Not allowed to see this person's leave");
        }
        return requests(" where r.staff_id = ? order by r.start_on desc", staffId);
    }

    // ------------------------------------------------------------------ helpers

    private List<Balance> balances(Long staffId, int year) {
        String gender = jdbc.queryForObject("select gender from staff_members where id = ?", String.class, staffId);
        return jdbc.query("""
                select t.id, t.code, t.name, t.days_per_year,
                  coalesce((select sum(a.days) from leave_adjustments a where a.staff_id = ? and a.leave_type_id = t.id and a.year = ?), 0),
                  coalesce((select sum(r.days) from leave_requests r where r.staff_id = ? and r.leave_type_id = t.id and r.status = 'APPROVED'
                            and extract(year from r.start_on) = ?), 0),
                  coalesce((select sum(r.days) from leave_requests r where r.staff_id = ? and r.leave_type_id = t.id and r.status = 'SUBMITTED'
                            and extract(year from r.start_on) = ?), 0)
                from leave_types t where t.active and (t.only_gender is null or t.only_gender = ?) order by t.sort_order, t.name""", (rs, n) -> {
            Integer ent = (Integer) rs.getObject(4);
            int adj = rs.getInt(5), taken = rs.getInt(6), pending = rs.getInt(7);
            return new Balance(rs.getLong(1), rs.getString(2), rs.getString(3), ent, adj, taken, pending, ent == null ? null : ent + adj - taken);
        }, staffId, year, staffId, year, staffId, year, gender);
    }

    private static final String REQUEST = """
            select r.id, r.staff_id, m.full_name, m.staff_no, d.name as department, m.department_id, m.user_id, r.leave_type_id, t.name as leave_type,
                   r.start_on, r.end_on, r.days, r.reason, r.relief_name, r.status, r.submitted_by_name, r.submitted_at, r.decided_by_name, r.decided_at,
                   r.decision_note
            from leave_requests r join staff_members m on m.id = r.staff_id join leave_types t on t.id = r.leave_type_id
            left join departments d on d.id = m.department_id""";

    private List<RequestView> requests(String where, Object... args) {
        boolean hr = access.has(HR);
        Long me = CurrentUser.id().orElse(null);
        return jdbc.query(REQUEST + where, (rs, n) -> view(rs, hr, me), args);
    }

    private RequestView request(Long id) {
        return requests(" where r.id = ?", id).stream().findFirst().orElseThrow(() -> new NotFoundException("Leave request not found"));
    }

    private RequestView view(ResultSet rs, boolean hr, Long me) throws SQLException {
        Long dept = (Long) rs.getObject("department_id");
        Long owner = (Long) rs.getObject("user_id");
        boolean own = owner != null && owner.equals(me);
        boolean canDecide = "SUBMITTED".equals(rs.getString("status")) && !own && (hr || (dept != null && access.headsDepartment(dept)));
        return new RequestView(rs.getLong("id"), rs.getLong("staff_id"), rs.getString("full_name"), rs.getString("staff_no"), rs.getString("department"),
                rs.getLong("leave_type_id"), rs.getString("leave_type"), rs.getObject("start_on", LocalDate.class), rs.getObject("end_on", LocalDate.class),
                rs.getInt("days"), rs.getString("reason"), rs.getString("relief_name"), rs.getString("status"), rs.getString("submitted_by_name"),
                odt(rs.getTimestamp("submitted_at")), rs.getString("decided_by_name"), odt(rs.getTimestamp("decided_at")), rs.getString("decision_note"), canDecide);
    }

    private boolean headsDepartmentOf(Long staffId) {
        Long dept = jdbc.query("select department_id from staff_members where id = ?", (rs, n) -> (Long) rs.getObject(1), staffId).stream().findFirst().orElse(null);
        return dept != null && access.headsDepartment(dept);
    }

    /** The usual leave of the Employment and Labour Relations Act, added once per institution; HR changes it to its own rules. */
    private void seed() {
        Integer n = jdbc.queryForObject("select count(*) from leave_types", Integer.class);
        if (n != null && n > 0) return;
        Object[][] rows = {
                {"ANNUAL", "Annual leave", 28, null, true, 10},
                {"SICK", "Sick leave", null, null, false, 20},
                {"MATERNITY", "Maternity leave", 84, "F", true, 30},
                {"PATERNITY", "Paternity leave", 3, "M", false, 40},
                {"COMPASSIONATE", "Compassionate leave", 4, null, false, 50},
                {"STUDY", "Study leave", null, null, false, 60},
                {"UNPAID", "Leave without pay", null, null, false, 70}};
        for (Object[] r : rows) {
            jdbc.update("""
                    insert into leave_types (tenant_id, code, name, days_per_year, only_gender, calendar_days, sort_order)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) on conflict (tenant_id, code) do nothing""", r);
        }
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
