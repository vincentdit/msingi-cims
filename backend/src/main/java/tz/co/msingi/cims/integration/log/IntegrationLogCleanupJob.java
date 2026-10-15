package tz.co.msingi.cims.integration.log;

import tz.co.msingi.cims.platform.jobs.JobLock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes integration log entries older than the retention period (cims.platform.integration-log-retention-days). */
@Slf4j
@Component
public class IntegrationLogCleanupJob {

    private final JobLock jobLock;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int retentionDays;

    public IntegrationLogCleanupJob(JdbcTemplate jdbc, TransactionTemplate tx,
                                    @Value("${cims.platform.integration-log-retention-days:365}") int retentionDays, JobLock jobLock) {
        this.jobLock = jobLock;
        this.jdbc = jdbc;
        this.tx = tx;
        this.retentionDays = Math.max(30, retentionDays);
    }

    @Scheduled(cron = "${cims.platform.integration-log-cleanup-cron:0 40 3 * * *}", zone = "Africa/Dar_es_Salaam")
    public void run() {
        jobLock.runOnce("integration-log-cleanup", Duration.ofHours(1), Duration.ofMinutes(5), this::runNow);
    }

    /** One run of the job; the schedule above runs it through the job lock, so only one instance runs it at a time. */
    public void runNow() {
        // runs as the platform (no institution), which the integration log's row-level security allows
        Integer n = tx.execute(s -> jdbc.update("delete from integration_calls where occurred_at < now() - make_interval(days => ?)", retentionDays));
        if (n != null && n > 0) log.info("Removed {} integration log entries older than {} days", n, retentionDays);
    }
}
