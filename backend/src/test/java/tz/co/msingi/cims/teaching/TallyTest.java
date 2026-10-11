package tz.co.msingi.cims.teaching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TallyTest {

    @Test
    void lateCountsAsAttendedAndExcusedIsLeftOut() {
        Tally t = Tally.NONE.add(AttendanceRecord.Status.PRESENT).add(AttendanceRecord.Status.LATE).add(AttendanceRecord.Status.ABSENT)
                .add(AttendanceRecord.Status.EXCUSED);
        assertThat(t).isEqualTo(new Tally(3, 2, 1, 1));
        assertThat(t.percent()).isEqualByComparingTo("66.7");
        assertThat(t.below(new BigDecimal("75"))).isTrue();
        assertThat(t.below(new BigDecimal("66.7"))).isFalse();
    }

    @Test
    void nothingCountedIsNeitherAPercentageNorBelow() {
        Tally t = Tally.NONE.add(AttendanceRecord.Status.EXCUSED);
        assertThat(t.percent()).isNull();
        assertThat(t.below(new BigDecimal("75"))).isFalse();
        assertThat(Tally.average(List.of(t))).isNull();
    }

    @Test
    void tallyPerRegistrationAndClassAverage() {
        List<AttendanceRecord> recs = new ArrayList<>();
        recs.add(rec(1L, AttendanceRecord.Status.PRESENT));
        recs.add(rec(1L, AttendanceRecord.Status.PRESENT));
        recs.add(rec(2L, AttendanceRecord.Status.ABSENT));
        recs.add(rec(2L, AttendanceRecord.Status.PRESENT));
        var by = Tally.byRegistration(recs);
        assertThat(by.get(1L).percent()).isEqualByComparingTo("100");
        assertThat(by.get(2L).percent()).isEqualByComparingTo("50");
        assertThat(Tally.average(by.values())).isEqualByComparingTo("75");
    }

    private static AttendanceRecord rec(Long reg, AttendanceRecord.Status s) {
        AttendanceRecord r = new AttendanceRecord();
        r.setModuleRegistrationId(reg);
        r.setStatus(s);
        return r;
    }
}
