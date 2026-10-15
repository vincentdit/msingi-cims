package tz.co.msingi.cims.platform.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;

/**
 * Makes a scheduled job run on one backend instance at a time (NFR-SCL-01), in the way ShedLock does, on the job_locks table.
 * <ul>
 *   <li>An instance runs the job only if it takes the job's row; the others skip that run.</li>
 *   <li>{@code atMost}: if the holder dies, the lock frees itself after this time.</li>
 *   <li>{@code atLeast}: the lock is kept at least this long after it was taken, so an instance whose clock fires a few seconds
 *       later does not run the same job again.</li>
 * </ul>
 * Times come from the database clock, so instances whose clocks differ still agree.
 */
@Slf4j
@Component
public class JobLock {

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final boolean holdMinimum;
    private final String me;

    public JobLock(JdbcTemplate jdbc,
                   @Value("${cims.jobs.lock-enabled:true}") boolean enabled,
                   @Value("${cims.jobs.hold-minimum:true}") boolean holdMinimum) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.holdMinimum = holdMinimum;
        this.me = identity();
    }

    /** Runs {@code job} if this instance gets the lock; returns whether it ran. */
    public boolean runOnce(String name, Duration atMost, Duration atLeast, Runnable job) {
        if (!enabled) {
            job.run();
            return true;
        }
        if (!acquire(name, atMost)) {
            log.debug("Job {} is running or ran just now on another instance; skipped here", name);
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            release(name, holdMinimum ? atLeast : Duration.ZERO);
        }
    }

    boolean acquire(String name, Duration atMost) {
        int taken = jdbc.update("""
                insert into job_locks (name, locked_until, locked_at, locked_by)
                values (?, clock_timestamp() + make_interval(secs => ?), clock_timestamp(), ?)
                on conflict (name) do update
                   set locked_until = excluded.locked_until, locked_at = excluded.locked_at, locked_by = excluded.locked_by
                 where job_locks.locked_until <= clock_timestamp()
                """, name, atMost.toMillis() / 1000.0, me);
        return taken == 1;
    }

    void release(String name, Duration atLeast) {
        jdbc.update("""
                update job_locks
                   set locked_until = greatest(clock_timestamp(), locked_at + make_interval(secs => ?))
                 where name = ? and locked_by = ?
                """, atLeast.toMillis() / 1000.0, name, me);
    }

    private static String identity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String id = host + "/" + ManagementFactory.getRuntimeMXBean().getName();
        return id.length() > 120 ? id.substring(0, 120) : id;
    }
}
