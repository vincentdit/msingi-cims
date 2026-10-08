-- =====================================================================
-- CIMS V12: student portal (FR-STU-01/02), notices and student requests
-- to update personal details (FR-REG-08).
-- =====================================================================

-- Notices for students, optionally limited to a programme and/or year of study
CREATE TABLE notices (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    title              VARCHAR(150)  NOT NULL,
    body               VARCHAR(4000) NOT NULL,
    category           VARCHAR(12)   NOT NULL CHECK (category IN ('GENERAL', 'ACADEMIC', 'FINANCE', 'EXAMINATION', 'HOSTEL', 'EVENT')),
    programme_id       BIGINT        REFERENCES programmes (id),     -- null = every programme
    year_of_study      SMALLINT      CHECK (year_of_study BETWEEN 1 AND 8),  -- null = every year
    pinned             BOOLEAN       NOT NULL DEFAULT FALSE,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    publish_at         TIMESTAMPTZ,                                   -- set on publishing; may be in the future
    expires_at         TIMESTAMPTZ,
    sms_sent_count     INT           NOT NULL DEFAULT 0,
    created_by_name    VARCHAR(200)  NOT NULL,
    published_by_name  VARCHAR(200),
    withdrawn_at       TIMESTAMPTZ,
    withdrawn_by_name  VARCHAR(200),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_notices_published CHECK (status = 'DRAFT' OR publish_at IS NOT NULL),
    CONSTRAINT ck_notices_expiry CHECK (expires_at IS NULL OR publish_at IS NULL OR expires_at > publish_at)
);
CREATE INDEX ix_notices_live ON notices (tenant_id, status, publish_at);

CREATE TABLE notice_reads (
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id),
    notice_id  BIGINT       NOT NULL REFERENCES notices (id),
    user_id    BIGINT       NOT NULL REFERENCES user_accounts (id),
    read_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (notice_id, user_id)
);

-- A student's request to correct personal details; Registry approves (FR-REG-08)
CREATE TABLE student_change_requests (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    student_id        BIGINT        NOT NULL REFERENCES students (id),
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    changes           JSONB         NOT NULL,     -- {"field": {"from": "...", "to": "..."}}
    reason            VARCHAR(500)  NOT NULL,
    needs_documents   BOOLEAN       NOT NULL DEFAULT FALSE,   -- name or date of birth: originals to be seen by Registry
    submitted_at      TIMESTAMPTZ   NOT NULL,
    decided_at        TIMESTAMPTZ,
    decided_by_name   VARCHAR(200),
    decision_note     VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_student_change_requests_decided CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_student_change_requests_pending ON student_change_requests (student_id) WHERE status = 'PENDING';
CREATE INDEX ix_student_change_requests_status ON student_change_requests (tenant_id, status);

CREATE INDEX ix_students_user ON students (user_id);

-- Students sign in with their registration number as well as their phone or email.
-- Migrations run as the application role, so row-level security is lifted for this statement only.
ALTER TABLE user_accounts NO FORCE ROW LEVEL SECURITY;
ALTER TABLE students NO FORCE ROW LEVEL SECURITY;
UPDATE user_accounts u SET username = s.registration_no
FROM students s
WHERE s.user_id = u.id
  AND NOT EXISTS (SELECT 1 FROM user_accounts o WHERE o.tenant_id = u.tenant_id AND lower(o.username) = lower(s.registration_no));
ALTER TABLE user_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE students FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['notices', 'notice_reads', 'student_change_requests']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
