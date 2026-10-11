-- =====================================================================
-- CIMS V17: teaching sessions and attendance (FR-TCH-02/03/04, BP-07,
-- BR-15) and examination eligibility with exemptions (FR-EXM-04,
-- BRU-08).
-- =====================================================================

-- The institution's eligibility rules (BRU-08)
ALTER TABLE exam_settings ADD COLUMN min_attendance_percent NUMERIC(5,2) NOT NULL DEFAULT 75
    CHECK (min_attendance_percent BETWEEN 0 AND 100);
ALTER TABLE exam_settings ADD COLUMN eligibility_requires_ca BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE exam_settings ADD COLUMN eligibility_requires_clearance BOOLEAN NOT NULL DEFAULT TRUE;
-- Students are warned once they fall below the threshold after this many sessions
ALTER TABLE exam_settings ADD COLUMN attendance_alert_after_sessions SMALLINT NOT NULL DEFAULT 4
    CHECK (attendance_alert_after_sessions BETWEEN 1 AND 50);

-- A class meeting of a module offering
CREATE TABLE class_sessions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    offering_id        BIGINT        NOT NULL REFERENCES module_offerings (id),
    session_date       DATE          NOT NULL,
    start_time         TIME          NOT NULL,
    duration_minutes   SMALLINT      NOT NULL CHECK (duration_minutes BETWEEN 15 AND 600),
    kind               VARCHAR(10)   NOT NULL CHECK (kind IN ('LECTURE', 'TUTORIAL', 'PRACTICAL', 'SEMINAR')),
    topic              VARCHAR(200),
    client_ref         UUID          NOT NULL,             -- set by the device, so a retried save is not recorded twice
    recorded_by_id     BIGINT        NOT NULL,
    recorded_by_name   VARCHAR(150)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_class_sessions_slot UNIQUE (tenant_id, offering_id, session_date, start_time),
    CONSTRAINT uq_class_sessions_ref UNIQUE (tenant_id, client_ref)
);
CREATE INDEX ix_class_sessions_offering ON class_sessions (tenant_id, offering_id, session_date);

-- One student's attendance at a session
CREATE TABLE attendance_records (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES tenants (id),
    session_id               BIGINT       NOT NULL REFERENCES class_sessions (id) ON DELETE CASCADE,
    module_registration_id   BIGINT       NOT NULL REFERENCES module_registrations (id),
    student_id               BIGINT       NOT NULL REFERENCES students (id),
    status                   VARCHAR(8)   NOT NULL CHECK (status IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')),
    note                     VARCHAR(200),
    CONSTRAINT uq_attendance_records UNIQUE (session_id, module_registration_id)
);
CREATE INDEX ix_attendance_records_student ON attendance_records (tenant_id, student_id);

-- A student warned about low attendance in a module (once per module)
CREATE TABLE attendance_alerts (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES tenants (id),
    offering_id              BIGINT       NOT NULL REFERENCES module_offerings (id),
    module_registration_id   BIGINT       NOT NULL REFERENCES module_registrations (id),
    student_id               BIGINT       NOT NULL REFERENCES students (id),
    percent                  NUMERIC(5,1) NOT NULL,
    alerted_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_alerts UNIQUE (tenant_id, module_registration_id)
);

-- Examination eligibility per student per module offering, from the latest list the Examinations Officer generated
CREATE TABLE exam_eligibility (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT        NOT NULL REFERENCES tenants (id),
    offering_id              BIGINT        NOT NULL REFERENCES module_offerings (id),
    module_registration_id   BIGINT        NOT NULL REFERENCES module_registrations (id),
    student_id               BIGINT        NOT NULL REFERENCES students (id),
    sessions_counted         INTEGER       NOT NULL DEFAULT 0,
    sessions_attended        INTEGER       NOT NULL DEFAULT 0,
    attendance_percent       NUMERIC(5,1),
    attendance_met           BOOLEAN       NOT NULL,
    ca_met                   BOOLEAN       NOT NULL,
    clearance_met            BOOLEAN       NOT NULL,
    rules_met                BOOLEAN       NOT NULL,
    exempted                 BOOLEAN       NOT NULL DEFAULT FALSE,
    eligible                 BOOLEAN       NOT NULL,
    eligibility_detail       JSONB         NOT NULL DEFAULT '[]',   -- the reasons a rule was not met
    generated_by_name        VARCHAR(150)  NOT NULL,
    generated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_exam_eligibility UNIQUE (tenant_id, module_registration_id)
);
CREATE INDEX ix_exam_eligibility_offering ON exam_eligibility (tenant_id, offering_id);
CREATE INDEX ix_exam_eligibility_student ON exam_eligibility (tenant_id, student_id);

-- An exemption from the eligibility rules: requested with a reason, decided by someone else
CREATE TABLE eligibility_exemptions (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT         NOT NULL REFERENCES tenants (id),
    offering_id              BIGINT         NOT NULL REFERENCES module_offerings (id),
    module_registration_id   BIGINT         NOT NULL REFERENCES module_registrations (id),
    student_id               BIGINT         NOT NULL REFERENCES students (id),
    reason                   VARCHAR(1000)  NOT NULL,
    status                   VARCHAR(8)     NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by_id          BIGINT         NOT NULL,
    requested_by_name        VARCHAR(150)   NOT NULL,
    requested_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    decided_by_name          VARCHAR(150),
    decided_at               TIMESTAMPTZ,
    decision_note            VARCHAR(1000),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                  BIGINT         NOT NULL DEFAULT 0
);
-- At most one open or granted exemption per student per module
CREATE UNIQUE INDEX uq_eligibility_exemptions_open ON eligibility_exemptions (tenant_id, module_registration_id)
    WHERE status IN ('PENDING', 'APPROVED');
CREATE INDEX ix_eligibility_exemptions_status ON eligibility_exemptions (tenant_id, status);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['class_sessions', 'attendance_records', 'attendance_alerts', 'exam_eligibility', 'eligibility_exemptions']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
