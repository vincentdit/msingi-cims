package tz.co.msingi.cims.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.platform.jobs.JobLock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Scheduled jobs run on one instance at a time (NFR-SCL-01): the job lock behaves like ShedLock. */
@IntegrationTest
class JobLockIT {

    @Autowired JdbcTemplate jdbc;

    JobLock lock;   // the minimum hold switched on, as in production (the test profile switches it off for the jobs)

    @BeforeEach
    void setUp() {
        jdbc.update("delete from job_locks");
        lock = new JobLock(jdbc, true, true);
    }

    @Test
    void onlyOneOfSeveralInstancesRunsTheJob() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ofSeconds(30), () -> {
                    runs.incrementAndGet();
                    sleep(300);
                });
            }));
        }
        start.countDown();
        int ran = 0;
        for (Future<Boolean> f : results) if (f.get(20, TimeUnit.SECONDS)) ran++;
        pool.shutdown();
        assertThat(ran).isEqualTo(1);
        assertThat(runs).hasValue(1);
    }

    @Test
    void aJobJustRunIsNotRunAgainWithinTheMinimumHold() {
        AtomicInteger runs = new AtomicInteger();
        assertThat(lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ofSeconds(30), runs::incrementAndGet)).isTrue();
        // another instance whose clock fires a moment later
        assertThat(lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ofSeconds(30), runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(1);
        // other jobs are not affected
        assertThat(lock.runOnce("other-job", Duration.ofMinutes(1), Duration.ofSeconds(30), runs::incrementAndGet)).isTrue();
    }

    @Test
    void withoutAMinimumHoldTheNextRunGoesAhead() {
        AtomicInteger runs = new AtomicInteger();
        lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, runs::incrementAndGet);
        lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    void aLockLeftByACrashedInstanceFreesItselfAfterTheMaximum() {
        // an instance took the lock and died without releasing it
        jdbc.update("insert into job_locks (name, locked_until, locked_at, locked_by) values "
                + "('test-job', clock_timestamp() + interval '1 second', clock_timestamp(), 'crashed-host/1')");
        AtomicInteger runs = new AtomicInteger();
        assertThat(lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, runs::incrementAndGet)).isFalse();
        sleep(1300);
        assertThat(lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void aFailingJobStillReleasesTheLock() {
        try {
            lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // the job's own error reaches the scheduler, which logs it
        }
        AtomicInteger runs = new AtomicInteger();
        assertThat(lock.runOnce("test-job", Duration.ofMinutes(1), Duration.ZERO, runs::incrementAndGet)).isTrue();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
