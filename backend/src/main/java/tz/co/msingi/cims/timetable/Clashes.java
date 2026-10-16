package tz.co.msingi.cims.timetable;

import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.timetable.TimetableData.Offering;
import tz.co.msingi.cims.timetable.TimetableDtos.Cohort;
import tz.co.msingi.cims.timetable.TimetableDtos.Warning;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The timetable rules (FR-TCH-05, FR-EXM-15, BR-43).
 * <ul>
 *   <li>Refused when saving: a venue holding two classes at once; a lecturer or invigilator in two places at once; two classes of one
 *   module at once unless they are for different groups; a registered student with two examination papers at once.</li>
 *   <li>Shown to look at: registered students or a planned class (programme and year) with two classes at once, more students than the
 *   room holds, modules without a class or paper, and students with two papers on one day.</li>
 * </ul>
 */
final class Clashes {

    static final String[] DAYS = {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");

    private Clashes() {
    }

    record ClassItem(Long id, Long offeringId, int day, LocalTime start, LocalTime end, Long venueId, String venueCode, int capacity, Long lecturerId,
                     String group) {

        boolean overlaps(ClassItem o) {
            return day == o.day && start.isBefore(o.end) && o.start.isBefore(end);
        }

        String when() {
            return DAYS[day] + " " + HM.format(start) + "–" + HM.format(end);
        }
    }

    record ExamItem(Long id, Long offeringId, String paper, LocalDate date, LocalTime start, LocalTime end, Set<Long> venueIds, int seats,
                    Set<Long> invigilators) {

        boolean overlaps(ExamItem o) {
            return date.equals(o.date) && start.isBefore(o.end) && o.start.isBefore(end);
        }

        String when() {
            return DAY.format(date) + " " + HM.format(start) + "–" + HM.format(end);
        }
    }

    /** Who teaches a class: its named lecturer, otherwise every lecturer allocated to the module. */
    static Set<Long> teachers(ClassItem c, Map<Long, Offering> offerings) {
        if (c.lecturerId() != null) return Set.of(c.lecturerId());
        Offering o = offerings.get(c.offeringId());
        return o == null ? Set.of() : o.lecturerIds();
    }

    // ------------------------------------------------------------------ refused when saving

    static void checkClass(ClassItem c, Collection<ClassItem> others, Map<Long, Offering> offerings, Map<Long, String> names) {
        Offering mine = offerings.get(c.offeringId());
        Set<Long> myTeachers = teachers(c, offerings);
        List<ClassItem> overlapping = others.stream().filter(o -> !Objects.equals(o.id(), c.id()) && c.overlaps(o)).toList();
        for (ClassItem o : overlapping) {
            if (o.venueId().equals(c.venueId())) {
                throw new BusinessRuleException("VENUE_CLASH", c.venueCode() + " already has " + what(offerings, o) + " on " + o.when() + ".");
            }
        }
        for (ClassItem o : overlapping) {
            Set<Long> both = new HashSet<>(myTeachers);
            both.retainAll(teachers(o, offerings));
            if (!both.isEmpty()) {
                String who = names.getOrDefault(both.iterator().next(), "The lecturer");
                throw new BusinessRuleException("LECTURER_CLASH", who + " already teaches " + what(offerings, o) + " on " + o.when()
                        + (c.lecturerId() == null ? ". Name the lecturer who takes this class if the module has several." : "."));
            }
        }
        for (ClassItem o : overlapping) {
            if (o.offeringId().equals(c.offeringId()) && (c.group() == null || o.group() == null || c.group().equalsIgnoreCase(o.group()))) {
                throw new BusinessRuleException("MODULE_OVERLAP", (mine == null ? "This module" : mine.code()) + " already has a class on " + o.when()
                        + ". Two classes of one module can run at once only for different groups.");
            }
        }
    }

    private static String what(Map<Long, Offering> offerings, ClassItem o) {
        Offering other = offerings.get(o.offeringId());
        return other == null ? "another class" : other.label();
    }

    static void checkExam(ExamItem e, Collection<ExamItem> others, Map<Long, Offering> offerings, Map<Long, String> names) {
        Offering mine = offerings.get(e.offeringId());
        List<ExamItem> overlapping = others.stream().filter(o -> !Objects.equals(o.id(), e.id()) && e.overlaps(o)).toList();
        for (ExamItem o : overlapping) {
            Set<Long> both = new HashSet<>(e.invigilators());
            both.retainAll(o.invigilators());
            if (!both.isEmpty()) {
                throw new BusinessRuleException("INVIGILATOR_CLASH", names.getOrDefault(both.iterator().next(), "An invigilator")
                        + " already invigilates " + label(offerings, o) + " on " + o.when() + ".");
            }
        }
        for (ExamItem o : overlapping) {
            if (o.offeringId().equals(e.offeringId())) {
                throw new BusinessRuleException("MODULE_OVERLAP", (mine == null ? "This module" : mine.code()) + " already has " + o.paper() + " on "
                        + o.when() + ".");
            }
        }
        for (ExamItem o : overlapping) {
            int common = common(mine, offerings.get(o.offeringId()));
            if (common > 0) {
                throw new BusinessRuleException("STUDENT_EXAM_CLASH", common + (common == 1 ? " student registered for " : " students registered for ")
                        + (mine == null ? "this module" : mine.code()) + " also " + (common == 1 ? "sits " : "sit ") + label(offerings, o) + " on "
                        + o.when() + ".");
            }
        }
    }

    /** Registered students who would sit two papers at once (checked again when the timetable is published). */
    static List<String> examStudentClashes(List<ExamItem> items, Map<Long, Offering> offerings) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                ExamItem a = items.get(i), b = items.get(j);
                if (a.offeringId().equals(b.offeringId()) || !a.overlaps(b)) continue;
                int n = common(offerings.get(a.offeringId()), offerings.get(b.offeringId()));
                if (n > 0) out.add(label(offerings, a) + " and " + label(offerings, b) + " (" + a.when() + "): " + n + " student(s)");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ shown to look at

    static List<Warning> classWarnings(List<ClassItem> items, Map<Long, Offering> offerings) {
        List<Warning> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                ClassItem a = items.get(i), b = items.get(j);
                if (!a.overlaps(b)) continue;
                Offering oa = offerings.get(a.offeringId()), ob = offerings.get(b.offeringId());
                if (oa == null || ob == null) continue;
                List<Long> ids = List.of(a.id(), b.id());
                if (a.venueId().equals(b.venueId())) {
                    out.add(new Warning("VENUE_CLASH", "error", a.venueCode() + " holds " + oa.code() + " and " + ob.code() + " on " + a.when() + ".", ids));
                    continue;
                }
                Set<Long> both = new HashSet<>(teachers(a, offerings));
                both.retainAll(teachers(b, offerings));
                if (!both.isEmpty() && !(oa.id().equals(ob.id()) && a.lecturerId() == null && b.lecturerId() == null && oa.lecturers().size() > 1)) {
                    out.add(new Warning("LECTURER_CLASH", "error", "A lecturer of " + oa.code() + (oa.id().equals(ob.id()) ? "" : " and " + ob.code())
                            + " is in two places on " + a.when() + ".", ids));
                }
                if (oa.id().equals(ob.id())) continue;
                boolean grouped = a.group() != null || b.group() != null;
                int common = common(oa, ob);
                if (common > 0) {
                    out.add(new Warning("STUDENT_CLASH", grouped ? "info" : "warning", common + (common == 1 ? " registered student takes " : " registered students take ")
                            + oa.code() + " and " + ob.code() + ", both on " + a.when() + (grouped ? " (fine if they are in different groups)." : "."), ids));
                } else if (oa.students().isEmpty() || ob.students().isEmpty()) {
                    for (Cohort ca : oa.cohorts()) {
                        for (Cohort cb : ob.cohorts()) {
                            if (!ca.programmeId().equals(cb.programmeId()) || ca.yearOfStudy() != cb.yearOfStudy()) continue;
                            if ("ELECTIVE".equals(ca.category()) && "ELECTIVE".equals(cb.category())) continue;
                            boolean core = "CORE".equals(ca.category()) && "CORE".equals(cb.category());
                            out.add(new Warning("COHORT_CLASH", grouped ? "info" : "warning", oa.code() + " and " + ob.code() + " are "
                                    + (core ? "both core" : "taken together") + " for " + ca.programmeCode() + " year " + ca.yearOfStudy() + ", and both are on "
                                    + a.when() + (core ? "." : " (students taking the elective cannot attend both)."), ids));
                        }
                    }
                }
            }
        }
        for (ClassItem c : items) {
            Offering o = offerings.get(c.offeringId());
            if (o != null && c.group() == null && o.students().size() > c.capacity()) {
                out.add(new Warning("OVER_CAPACITY", "warning", c.venueCode() + " holds " + c.capacity() + " but " + o.students().size()
                        + " students are registered for " + o.code() + " (" + c.when() + ").", List.of(c.id())));
            }
        }
        Set<Long> scheduled = new HashSet<>();
        items.forEach(c -> scheduled.add(c.offeringId()));
        List<Long> missing = offerings.values().stream().filter(o -> !scheduled.contains(o.id())).map(Offering::id).toList();
        if (!missing.isEmpty()) {
            out.add(new Warning("UNSCHEDULED", "info", missing.size() + (missing.size() == 1 ? " module has" : " modules have") + " no class yet: "
                    + String.join(", ", missing.stream().limit(12).map(id -> offerings.get(id).code()).toList()) + (missing.size() > 12 ? ", …" : "") + ".", missing));
        }
        return out;
    }

    static List<Warning> examWarnings(List<ExamItem> items, Map<Long, Offering> offerings, Map<Long, String> venueCodes) {
        List<Warning> out = new ArrayList<>();
        for (String s : examStudentClashes(items, offerings)) out.add(new Warning("STUDENT_EXAM_CLASH", "error", "Two papers at once: " + s + ".", List.of()));
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                ExamItem a = items.get(i), b = items.get(j);
                if (!a.overlaps(b)) continue;
                Set<Long> shared = new HashSet<>(a.venueIds());
                shared.retainAll(b.venueIds());
                if (!shared.isEmpty()) {
                    int candidates = size(offerings.get(a.offeringId())) + size(offerings.get(b.offeringId()));
                    int seats = shared.size() == a.venueIds().size() && shared.size() == b.venueIds().size() ? a.seats() : Math.max(a.seats(), b.seats());
                    if (candidates > seats) {
                        out.add(new Warning("SHARED_VENUE", "warning", label(offerings, a) + " and " + label(offerings, b) + " share "
                                + String.join(", ", shared.stream().map(v -> venueCodes.getOrDefault(v, "a venue")).toList()) + " on " + a.when() + ": "
                                + candidates + " candidates for " + seats + " seats.", List.of(a.id(), b.id())));
                    }
                }
            }
        }
        for (ExamItem e : items) {
            int n = size(offerings.get(e.offeringId()));
            if (n > e.seats()) {
                out.add(new Warning("OVER_CAPACITY", "warning", label(offerings, e) + ": " + n + " candidates but " + e.seats() + " seats ("
                        + e.when() + "). Add a venue.", List.of(e.id())));
            }
        }
        // two papers on one day for the same student
        Map<Long, Set<LocalDate>> seen = new java.util.HashMap<>();
        Set<Long> twice = new HashSet<>();
        for (ExamItem e : items) {
            Offering o = offerings.get(e.offeringId());
            if (o == null) continue;
            for (Long st : o.students()) if (!seen.computeIfAbsent(st, k -> new HashSet<>()).add(e.date())) twice.add(st);
        }
        if (!twice.isEmpty()) {
            out.add(new Warning("SAME_DAY", "info", twice.size() + (twice.size() == 1 ? " student has" : " students have") + " two or more papers on one day.", List.of()));
        }
        Set<Long> scheduled = new HashSet<>();
        items.forEach(e -> scheduled.add(e.offeringId()));
        List<Long> missing = offerings.values().stream().filter(o -> !o.students().isEmpty() && !scheduled.contains(o.id())).map(Offering::id).toList();
        if (!missing.isEmpty()) {
            out.add(new Warning("UNSCHEDULED", "info", missing.size() + (missing.size() == 1 ? " module with" : " modules with") + " registered students "
                    + (missing.size() == 1 ? "has" : "have") + " no paper yet: " + String.join(", ", missing.stream().limit(12).map(id -> offerings.get(id).code()).toList())
                    + (missing.size() > 12 ? ", …" : "") + ".", missing));
        }
        return out;
    }

    private static int common(Offering a, Offering b) {
        if (a == null || b == null) return 0;
        Set<Long> s = new HashSet<>(a.students());
        s.retainAll(b.students());
        return s.size();
    }

    private static int size(Offering o) {
        return o == null ? 0 : o.students().size();
    }

    private static String label(Map<Long, Offering> offerings, ExamItem e) {
        Offering o = offerings.get(e.offeringId());
        return (o == null ? "?" : o.code()) + " " + e.paper();
    }
}
