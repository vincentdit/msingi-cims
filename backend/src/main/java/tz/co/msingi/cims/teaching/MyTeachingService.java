package tz.co.msingi.cims.teaching;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.OfferingSummary;
import tz.co.msingi.cims.exams.ExamDtos.Person;
import tz.co.msingi.cims.exams.OfferingService;
import tz.co.msingi.cims.exams.RegulationService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.teaching.TeachingDtos.AssignedRemark;
import tz.co.msingi.cims.teaching.TeachingDtos.AtRisk;
import tz.co.msingi.cims.teaching.TeachingDtos.AttendanceView;
import tz.co.msingi.cims.teaching.TeachingDtos.MyModule;
import tz.co.msingi.cims.teaching.TeachingDtos.MyTeaching;
import tz.co.msingi.cims.teaching.TeachingDtos.Step;
import tz.co.msingi.cims.teaching.TeachingDtos.StudentAttendance;
import tz.co.msingi.cims.teaching.TeachingDtos.TeachingSemester;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The lecturer's landing page (BR-14, BP-07 steps 2-3, BP-08 steps 1-3): for one semester, each module allocated to them with its
 * class, attendance so far, the assessment plan and how much of the CA and SE marks are in, where the results stand in moderation
 * (and any comment from the Head of Department), examination eligibility, supplementary marks to enter, remarks assigned to them,
 * the students at risk across their modules, and the next things to do, each pointing to its page.
 */
@Service
@RequiredArgsConstructor
public class MyTeachingService {

    private static final int STALE_DAYS = 7;
    private static final int CLOSING_SOON_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final OfferingService offerings;
    private final AttendanceService attendance;
    private final RegulationService regulations;
    private final tz.co.msingi.cims.exams.competence.CompetenceService competenceUnits;

    @Transactional(readOnly = true)
    public MyTeaching board(Long semesterId) {
        Long me = CurrentUser.requireId();
        BigDecimal threshold = regulations.settings().getMinAttendancePercent();
        List<TeachingSemester> semesters = jdbc.query("""
                select distinct s.id, s.name, y.code, s.start_date, s.end_date from semesters s join academic_years y on y.id = s.academic_year_id
                where s.id in (select o.semester_id from offering_staff st join module_offerings o on o.id = st.offering_id where st.user_id = ?)
                   or s.id in (select u.semester_id from competence_unit_assessors a join competence_units u on u.id = a.unit_id where a.user_id = ?)
                order by s.start_date desc""",
                (r, i) -> new TeachingSemester(r.getLong("id"), r.getString("name"), r.getString("code"), r.getObject("start_date", LocalDate.class),
                        r.getObject("end_date", LocalDate.class)), me, me);
        List<AssignedRemark> remarks = jdbc.query("""
                select a.id, m.code, m.title, a.kind, a.assigned_at from result_appeals a join module_offerings o on o.id = a.offering_id
                join modules m on m.id = o.module_id where a.examiner_user_id = ? and a.status = 'ASSIGNED' order by a.assigned_at""",
                (r, i) -> new AssignedRemark(r.getLong("id"), r.getString("code"), r.getString("title"), r.getString("kind"),
                        r.getObject("assigned_at", OffsetDateTime.class)), me);
        if (semesters.isEmpty()) {
            List<Step> next = new ArrayList<>(remarkSteps(remarks));
            next.add(new Step("info", "No modules are allocated to you yet. Your Head of Department allocates modules each semester.", null));
            return new MyTeaching(List.of(), null, null, null, "NOT_SET", null, threshold, 0, 0, BigDecimal.ZERO, List.of(), List.of(), List.of(), remarks, next);
        }
        LocalDate today = LocalDate.now(AttendanceService.ZONE);
        TeachingSemester sem = semesterId != null
                ? semesters.stream().filter(s -> s.id().equals(semesterId)).findFirst().orElseThrow(() -> new NotFoundException("Semester", semesterId))
                : semesters.stream().filter(s -> started(s, today) && !ended(s, today)).findFirst()
                .orElse(semesters.stream().filter(s -> started(s, today)).findFirst().orElse(semesters.get(semesters.size() - 1)));

        Map<String, Object> w = jdbc.queryForMap("select marks_entry_opens, marks_entry_closes from semesters where id = ?", sem.id());
        LocalDate opens = date(w.get("marks_entry_opens")), closes = date(w.get("marks_entry_closes"));
        String window = opens == null && closes == null ? "NOT_SET" : opens != null && today.isBefore(opens) ? "UPCOMING"
                : closes != null && today.isAfter(closes) ? "CLOSED" : "OPEN";
        Integer daysToClose = closes == null || today.isAfter(closes) ? null : (int) ChronoUnit.DAYS.between(today, closes);

        List<MyModule> modules = new ArrayList<>();
        List<AtRisk> atRisk = new ArrayList<>();
        for (OfferingSummary o : offerings.list(sem.id(), true)) {
            if (!o.mine()) continue;
            AttendanceView a = attendance.view(o.id());
            Map<String, Object> m = jdbc.queryForMap("""
                    select o.returned_by_name, o.resit_status, o.resit_returned_note,
                      (select count(*) from assessment_components c where c.offering_id = o.id and c.category = 'CA') as ca_items,
                      (select count(*) from assessment_components c where c.offering_id = o.id and c.category = 'SE') as se_items,
                      (select count(*) from assessment_scores s join assessment_components c on c.id = s.component_id
                         where c.offering_id = o.id and c.category = 'CA') as ca_scores,
                      (select count(*) from assessment_scores s join assessment_components c on c.id = s.component_id
                         where c.offering_id = o.id and c.category = 'SE') as se_scores,
                      (select count(*) from exam_eligibility e where e.offering_id = o.id) as elig,
                      (select count(*) from exam_eligibility e where e.offering_id = o.id and not e.eligible) as not_elig,
                      (select count(*) from resits r where r.offering_id = o.id and r.status = 'REGISTERED' and (r.fee = 0 or r.invoice_id is null
                         or exists (select 1 from invoices i where i.id = r.invoice_id and i.status = 'PAID'))) as resits,
                      (select s.id from class_sessions s where s.offering_id = o.id and s.checkin_opened_at is not null
                         and s.checkin_closed_at is null order by s.id desc limit 1) as checkin
                    from module_offerings o where o.id = ?""", o.id());
            int n = a.students().size();
            List<String> co = o.lecturers().stream().filter(p -> !p.id().equals(me)).map(Person::fullName).toList();
            List<String> programmes = a.students().stream().map(StudentAttendance::programmeCode).filter(Objects::nonNull).distinct().sorted().toList();
            LocalDate last = a.sessionList().isEmpty() ? null : a.sessionList().get(0).sessionDate();
            modules.add(new MyModule(o.id(), o.code(), o.title(), o.credits(), o.departmentName(), co, programmes, n, a.sessions(), a.minutesTaught(),
                    last, a.averagePercent(), a.below(), o.status(), o.returnedNote(), (String) m.get("returned_by_name"),
                    num(m, "ca_items") + num(m, "se_items") > 0, percent(num(m, "ca_scores"), num(m, "ca_items") * n),
                    percent(num(m, "se_scores"), num(m, "se_items") * n), o.complete(), num(m, "elig") > 0, (int) num(m, "not_elig"),
                    (String) m.get("resit_status"), (int) num(m, "resits"), (String) m.get("resit_returned_note"),
                    m.get("checkin") == null ? null : ((Number) m.get("checkin")).longValue(), a.canRecord()));
            a.students().stream().filter(StudentAttendance::below).forEach(s -> atRisk.add(new AtRisk(o.id(), o.code(), s.studentId(), s.registrationNo(),
                    s.name(), s.percent(), s.attended(), s.counted(), s.alertedAt() != null)));
        }
        atRisk.sort(Comparator.comparing(AtRisk::percent, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AtRisk::registrationNo));

        int minutes = modules.stream().mapToInt(MyModule::minutesTaught).sum();
        var competence = competenceUnits.mine(sem.id());
        List<Step> next = next(sem, today, window, closes, daysToClose, modules, remarks);
        List<Step> unitSteps = new ArrayList<>();
        for (var u : competence) {
            String link = "/staff/competence/" + u.id();
            if ("DRAFT".equals(u.status()) && u.returnedNote() != null) unitSteps.add(new Step("error", u.code() + " was returned by the Head of Department: " + u.returnedNote(), link));
            if ("DRAFT".equals(u.status()) && u.incomplete() > 0) {
                unitSteps.add(new Step("warning", "Record competence outcomes for " + u.incomplete() + " trainee(s) in " + u.code() + ".", link));
            } else if ("DRAFT".equals(u.status()) && u.students() > 0) {
                unitSteps.add(new Step("info", "Every trainee in " + u.code() + " is assessed: submit it to the Head of Department.", link));
            }
        }
        if (!unitSteps.isEmpty()) {
            next.removeIf(x -> "success".equals(x.severity()));
            next.addAll(0, unitSteps);
        }
        return new MyTeaching(semesters, sem, opens, closes, window, daysToClose, threshold, modules.stream().mapToInt(MyModule::students).sum(),
                modules.stream().mapToInt(MyModule::sessions).sum(), BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP),
                modules, competence, atRisk.size() > 20 ? atRisk.subList(0, 20) : atRisk, remarks, next);
    }

    private static List<Step> next(TeachingSemester sem, LocalDate today, String window, LocalDate closes, Integer daysToClose,
                                   List<MyModule> modules, List<AssignedRemark> remarks) {
        List<Step> next = new ArrayList<>();
        boolean underWay = started(sem, today);
        for (MyModule m : modules) {
            String marks = "/staff/marks/" + m.offeringId(), teach = "/staff/teaching/" + m.offeringId();
            boolean draft = "DRAFT".equals(m.status());
            if (m.openCheckInSessionId() != null) next.add(new Step("info", "Self check-in for " + m.code() + " is open.", teach));
            if (draft && m.returnedNote() != null) {
                next.add(new Step("error", m.code() + " was returned" + (m.returnedByName() == null ? "" : " by " + m.returnedByName()) + ": "
                        + m.returnedNote(), marks));
            }
            if (draft && !m.planSet()) next.add(new Step("warning", "Set the assessment plan (CA and SE items) for " + m.code() + ".", marks));
            if (draft && underWay && m.students() > 0) {
                if (m.sessions() == 0) {
                    next.add(new Step("warning", "No attendance recorded for " + m.code() + " yet.", teach));
                } else if (m.lastSession() != null && !ended(sem, today) && ChronoUnit.DAYS.between(m.lastSession(), today) > STALE_DAYS) {
                    next.add(new Step("info", m.code() + ": attendance last recorded " + ChronoUnit.DAYS.between(m.lastSession(), today) + " days ago.", teach));
                }
            }
            if (m.below() > 0) {
                next.add(new Step("info", m.below() + (m.below() == 1 ? " student is" : " students are") + " below the attendance threshold in "
                        + m.code() + ".", teach));
            }
            if (draft && m.planSet() && m.students() > 0) {
                boolean closing = "OPEN".equals(window) && daysToClose != null && daysToClose <= CLOSING_SOON_DAYS;
                if ("CLOSED".equals(window)) {
                    next.add(new Step("error", "Marks entry closed on " + closes + " but " + m.code() + " is not submitted. Contact the Examinations Office.", marks));
                } else if (closing) {
                    next.add(new Step("warning", "Marks entry closes " + (daysToClose == 0 ? "today" : "in " + daysToClose + (daysToClose == 1 ? " day" : " days"))
                            + ": " + m.code() + " has CA " + pct(m.caPercent()) + " and SE " + pct(m.sePercent()) + " entered.", marks));
                } else if (m.complete() >= m.students() && "OPEN".equals(window)) {
                    next.add(new Step("info", "All marks for " + m.code() + " are in: check them and submit to the Head of Department.", marks));
                }
            }
            if (m.resitReturnedNote() != null && "DRAFT".equals(m.resitStatus())) {
                next.add(new Step("error", "Supplementary marks for " + m.code() + " were returned: " + m.resitReturnedNote(), marks));
            } else if (m.resitsToMark() > 0 && (m.resitStatus() == null || "DRAFT".equals(m.resitStatus()))) {
                next.add(new Step("warning", "Enter supplementary/special examination marks for " + m.resitsToMark() + " student(s) in " + m.code() + ".", marks));
            }
        }
        next.addAll(remarkSteps(remarks));
        if ("NOT_SET".equals(window) && modules.stream().anyMatch(m -> "DRAFT".equals(m.status()))) {
            next.add(new Step("info", "The Examinations Office has not set the marks entry window for " + sem.name() + " yet.", null));
        }
        if (next.isEmpty()) next.add(new Step("success", "Nothing is waiting for you this semester.", null));
        return next;
    }

    private static List<Step> remarkSteps(List<AssignedRemark> remarks) {
        return remarks.stream().map(r -> new Step("warning", ("REMARK".equals(r.kind()) ? "Re-mark the examination script" : "Review the appeal")
                + " in " + r.moduleCode() + " assigned to you by the Examinations Office.", "/staff/appeals")).toList();
    }

    private static boolean started(TeachingSemester s, LocalDate today) {
        return s.startDate() == null || !s.startDate().isAfter(today);
    }

    private static boolean ended(TeachingSemester s, LocalDate today) {
        return s.endDate() != null && s.endDate().isBefore(today);
    }

    private static BigDecimal percent(long part, long whole) {
        if (whole <= 0) return null;
        return BigDecimal.valueOf(Math.min(part, whole) * 100).divide(BigDecimal.valueOf(whole), 0, RoundingMode.DOWN);
    }

    private static String pct(BigDecimal p) {
        return p == null ? "—" : p.toPlainString() + "%";
    }

    private static long num(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number x ? x.longValue() : 0;
    }

    private static LocalDate date(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return (LocalDate) o;
    }
}
