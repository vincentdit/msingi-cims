-- Lecturer: self check-in by QR code or a rotating code (FR-TCH-03, optional in the SRS).
-- The lecturer opens check-in for a class in progress; students in the class check in from the student portal with the
-- code on the lecturer's screen. The code changes every 20 seconds (HMAC of the session's secret and the time step).
-- When check-in closes, everyone in the class who did not check in is recorded absent and the lecturer can correct the register.

ALTER TABLE class_sessions
    ADD COLUMN checkin_secret       VARCHAR(64),
    ADD COLUMN checkin_opened_at    TIMESTAMPTZ,
    ADD COLUMN checkin_closes_at    TIMESTAMPTZ,
    ADD COLUMN checkin_late_after   SMALLINT CHECK (checkin_late_after BETWEEN 0 AND 120),
    ADD COLUMN checkin_closed_at    TIMESTAMPTZ;
CREATE INDEX ix_class_sessions_checkin_open ON class_sessions (tenant_id, checkin_closes_at)
    WHERE checkin_opened_at IS NOT NULL AND checkin_closed_at IS NULL;

ALTER TABLE attendance_records
    ADD COLUMN source         VARCHAR(8)  NOT NULL DEFAULT 'REGISTER' CHECK (source IN ('REGISTER', 'SELF')),
    ADD COLUMN checked_in_at  TIMESTAMPTZ,
    ADD COLUMN device_hash    VARCHAR(64);    -- SHA-256 of the student's device id, to flag one phone checking in several students

-- Every student check-in attempt, so wrong codes can be limited
CREATE TABLE checkin_attempts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants (id),
    student_id    BIGINT       NOT NULL REFERENCES students (id),
    session_id    BIGINT       REFERENCES class_sessions (id) ON DELETE CASCADE,
    succeeded     BOOLEAN      NOT NULL,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_checkin_attempts_student ON checkin_attempts (tenant_id, student_id, attempted_at);

ALTER TABLE checkin_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE checkin_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkin_attempts USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());
