package tz.co.msingi.cims.reporting;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/** Test access to the package-private schedule arithmetic. */
public final class ReportScheduleServiceAccess {
    private ReportScheduleServiceAccess() {
    }

    public static OffsetDateTime next(String f, Integer dow, Integer dom, int hour, ZonedDateTime now) {
        return ReportScheduleService.nextRun(f, dow, dom, hour, now);
    }
}
