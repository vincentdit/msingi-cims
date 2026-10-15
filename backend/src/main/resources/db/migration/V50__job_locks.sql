-- Scheduled jobs run once across several backend instances (NFR-SCL-01): an instance runs a job only after taking its row here.
-- A platform table (no tenant, no row-level security): one lock per job for the whole installation.
CREATE TABLE job_locks (
    name          VARCHAR(64)  PRIMARY KEY,
    locked_until  TIMESTAMPTZ  NOT NULL,      -- the lock is free once this time has passed
    locked_at     TIMESTAMPTZ  NOT NULL,      -- when the current or last holder took it
    locked_by     VARCHAR(120) NOT NULL       -- host and process of that holder
);
