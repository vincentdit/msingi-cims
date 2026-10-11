package tz.co.msingi.cims.teaching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A student's attendance in a module (FR-TCH-04): late counts as attended, excused sessions are left out, and sessions recorded
 * before the student joined the class are not counted at all.
 */
public record Tally(int counted, int attended, int absent, int excused) {

    public static final Tally NONE = new Tally(0, 0, 0, 0);

    /** Attendance as a percentage, to one decimal place; null when no session counts yet. */
    public BigDecimal percent() {
        if (counted == 0) return null;
        return BigDecimal.valueOf(attended * 100L).divide(BigDecimal.valueOf(counted), 1, RoundingMode.HALF_UP);
    }

    public boolean below(BigDecimal threshold) {
        BigDecimal p = percent();
        return p != null && p.compareTo(threshold) < 0;
    }

    Tally add(AttendanceRecord.Status s) {
        return switch (s) {
            case PRESENT, LATE -> new Tally(counted + 1, attended + 1, absent, excused);
            case ABSENT -> new Tally(counted + 1, attended, absent + 1, excused);
            case EXCUSED -> new Tally(counted, attended, absent, excused + 1);
        };
    }

    /** Tallies per module registration. */
    public static Map<Long, Tally> byRegistration(Collection<AttendanceRecord> records) {
        Map<Long, Tally> out = new HashMap<>();
        for (AttendanceRecord r : records) out.merge(r.getModuleRegistrationId(), NONE.add(r.getStatus()), Tally::plus);
        return out;
    }

    Tally plus(Tally o) {
        return new Tally(counted + o.counted, attended + o.attended, absent + o.absent, excused + o.excused);
    }

    /** The mean of the students' percentages (students with nothing counted are left out). */
    public static BigDecimal average(Collection<Tally> tallies) {
        var ps = tallies.stream().map(Tally::percent).filter(java.util.Objects::nonNull).toList();
        if (ps.isEmpty()) return null;
        return ps.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(ps.size()), 1, RoundingMode.HALF_UP);
    }
}
