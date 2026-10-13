package tz.co.msingi.cims.portal;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.exams.ResitService;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a student should do next, across the whole journey (FR-STU-01 to 04): first registration, semester registration, fees and
 * examination clearance, attendance, supplementary examinations, hostel, IPT, competence re-assessment and clearance for graduation.
 * Each step has a kind and values; the portal words it in English or Swahili.
 */
@Component
@RequiredArgsConstructor
public class StudentNextSteps {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final ResitService resits;
    private final tz.co.msingi.cims.teaching.AttendanceService attendance;

    public record Step(String kind, String severity, Map<String, String> values, String link) {
    }

    public List<Step> forStudent(Student s, Statement st, List<Clearance> clearance, String firstRegistrationStatus) {
        LocalDate today = LocalDate.now(ZONE);
        List<Step> out = new ArrayList<>();
        if (firstRegistrationStatus != null && !"APPROVED".equals(firstRegistrationStatus)) {
            out.add(new Step("FIRST_REGISTRATION", "warning", Map.of(), "/student/profile"));
        }

        // semester registration
        jdbc.query("""
                select se.id, se.name, y.code, se.registration_closes, coalesce(se.late_registration_closes, se.registration_closes) as last_day,
                       (select r.status from semester_registrations r where r.semester_id = se.id and r.student_id = ? order by r.id desc limit 1) as status
                from semesters se join academic_years y on y.id = se.academic_year_id
                where se.registration_opens <= ? and coalesce(se.late_registration_closes, se.registration_closes) >= ? order by se.registration_opens""", rs -> {
            String status = rs.getString("status");
            String semester = rs.getString("name") + " " + rs.getString("code");
            if (status == null || "REJECTED".equals(status) || "CANCELLED".equals(status)) {
                out.add(new Step("REGISTER_SEMESTER", "warning", Map.of("semester", semester, "closes", rs.getObject("last_day", LocalDate.class).toString()), "/student/register"));
            } else if ("SUBMITTED".equals(status)) {
                out.add(new Step("REGISTRATION_WAITING", "info", Map.of("semester", semester), "/student/register"));
            }
        }, s.getId(), today, today);

        // fees and examination clearance; a bed's invoice is its own step below (HOSTEL_PAY)
        List<Long> bedInvoices = jdbc.queryForList("select a.invoice_id from hostel_allocations a where a.student_id = ? and a.status = 'ALLOCATED' and a.invoice_id is not null",
                Long.class, s.getId());
        st.invoices().stream().filter(i -> i.balance().signum() > 0 && !List.of("CANCELLED", "CREDITED").contains(i.status()) && !bedInvoices.contains(i.id()))
                .min(java.util.Comparator.comparing(i -> i.dueDate() == null ? LocalDate.MAX : i.dueDate())).ifPresent(i -> {
                    Map<String, String> v = new LinkedHashMap<>();
                    v.put("amount", money(i.balance()));
                    v.put("description", i.description());
                    if (i.controlNo() != null) v.put("controlNo", i.controlNo());
                    if (i.dueDate() != null) v.put("due", i.dueDate().toString());
                    out.add(new Step(i.controlNo() == null ? "PAY_NEEDS_CONTROL" : "PAY", i.dueDate() != null && i.dueDate().isBefore(today) ? "error" : "info", v, "/student/fees"));
                });
        clearance.stream().filter(c -> "EXAMINATION".equals(c.gate()) && c.enabled() && !c.cleared() && c.amountNeeded() != null && c.amountNeeded().signum() > 0)
                .findFirst().ifPresent(c -> out.add(new Step("EXAM_CLEARANCE", "warning", Map.of("amount", money(c.amountNeeded())), "/student/fees")));
        if (st.sponsorPending() != null && st.sponsorPending().signum() > 0) {
            out.add(new Step("SPONSOR_PENDING", "info", Map.of("amount", money(st.sponsorPending())), "/student/fees"));
        }

        // attendance below the examination threshold in the current semester
        List<String> low = List.of();
        try {
            var att = attendance.mine(null);
            low = att.modules().stream().filter(m -> m.below() && m.counted() > 0).map(m -> m.code()).toList();
        } catch (RuntimeException ignored) {
            // not registered for a semester
        }
        if (!low.isEmpty()) out.add(new Step("ATTENDANCE_LOW", "warning", Map.of("modules", String.join(", ", low)), "/student"));

        // supplementary and special examinations
        try {
            var r = resits.mine();
            if (r.registrationOpen()) {
                List<String> open = r.modules().stream().filter(m -> m.resitId() == null).map(m -> m.code()).toList();
                if (!open.isEmpty()) {
                    out.add(new Step("RESIT_OPEN", "warning", Map.of("modules", String.join(", ", open), "closes", String.valueOf(r.registrationCloses())), "/student/results"));
                }
            }
        } catch (RuntimeException ignored) {
            // no resit session for this student
        }

        // NVA competence: units verified with tasks not yet competent
        List<String> nyc = jdbc.queryForList("""
                select distinct m.code from competence_assessments a join competence_tasks t on t.id = a.task_id join competence_units u on u.id = t.unit_id
                join modules m on m.id = u.module_id where a.student_id = ? and a.verified and a.outcome = 'NOT_YET_COMPETENT'
                  and a.attempt = (select max(b.attempt) from competence_assessments b where b.task_id = a.task_id and b.module_registration_id = a.module_registration_id)
                order by 1""", String.class, s.getId());
        if (!nyc.isEmpty()) out.add(new Step("COMPETENCE_NYC", "warning", Map.of("modules", String.join(", ", nyc)), "/student/results"));

        // hostel: an open window not applied to, or an allocation to pay for
        jdbc.query("""
                select w.name, w.closes_on from hostel_windows w where w.opens_on <= ? and w.closes_on >= ?
                  and not exists (select 1 from hostel_applications a where a.window_id = w.id and a.student_id = ? and a.status <> 'CANCELLED')
                order by w.closes_on limit 1""", rs -> {
            out.add(new Step("HOSTEL_APPLY", "info", Map.of("window", rs.getString(1), "closes", rs.getObject(2, LocalDate.class).toString()), "/student"));
        }, today, today, s.getId());
        jdbc.query("""
                select a.pay_by, i.total_amount - i.paid_amount as owed from hostel_allocations a join invoices i on i.id = a.invoice_id
                where a.student_id = ? and a.status = 'ALLOCATED' and i.status in ('ISSUED', 'PARTIALLY_PAID') limit 1""", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("amount", money(rs.getBigDecimal("owed")));
            if (rs.getObject("pay_by") != null) v.put("payBy", rs.getObject("pay_by", LocalDate.class).toString());
            out.add(new Step("HOSTEL_PAY", "warning", v, "/student/fees"));
        }, s.getId());

        // IPT: a period taking requests with no placement yet
        jdbc.query("""
                select p.name, p.requests_close from ipt_periods p where p.ends_on >= ? and (p.requests_close is null or p.requests_close >= ?)
                  and not exists (select 1 from ipt_placements x where x.period_id = p.id and x.student_id = ? and x.status not in ('CANCELLED', 'DECLINED'))
                  and (p.module_id is null or exists (select 1 from module_registrations mr join semester_registrations sr on sr.id = mr.semester_registration_id
                        where sr.student_id = ? and sr.semester_id = p.semester_id and mr.module_id = p.module_id and sr.status = 'APPROVED'))
                order by p.starts_on limit 1""", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("period", rs.getString(1));
            if (rs.getObject(2) != null) v.put("closes", rs.getObject(2, LocalDate.class).toString());
            out.add(new Step("IPT_REQUEST", "info", v, "/student"));
        }, today, today, s.getId(), s.getId());

        // clearance for graduation: offices holding the student
        jdbc.query("""
                select o.name, i.note from clearance_items i join clearances c on c.id = i.clearance_id join clearance_offices o on o.id = i.office_id
                where c.student_id = ? and i.status = 'HELD' order by o.name""", rs -> {
            out.add(new Step("CLEARANCE_HELD", "error", Map.of("office", rs.getString(1), "reason", rs.getString(2) == null ? "" : rs.getString(2)), "/student"));
        }, s.getId());

        if (out.isEmpty()) out.add(new Step("ALL_DONE", "success", Map.of(), null));
        return out;
    }

    private static String money(BigDecimal v) {
        return String.format(java.util.Locale.ENGLISH, "%,.0f", v);
    }
}
