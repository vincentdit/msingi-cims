-- BR-33 / FR-COM-01 / FR-INT-04: email beside SMS; FR-ADM-02: verified email addresses; FR-ADM-13: admission letters by email;
-- FR-RPT-03: reports sent by email on a schedule.

CREATE TABLE email_messages (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    to_address       VARCHAR(120)  NOT NULL,
    recipient_name   VARCHAR(200),
    kind             VARCHAR(10)   NOT NULL CHECK (kind IN ('EVENT', 'SECURITY', 'LETTER', 'REPORT')),
    template_code    VARCHAR(40),
    subject          VARCHAR(200)  NOT NULL,
    body             TEXT          NOT NULL,      -- verification codes are masked here
    attachment_name  VARCHAR(200),
    attachment_type  VARCHAR(100),
    attachment       BYTEA,                       -- kept only until sent, then cleared
    attachment_size  INT,
    status           VARCHAR(8)    NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    attempts         SMALLINT      NOT NULL DEFAULT 0,
    provider_ref     VARCHAR(120),
    error            VARCHAR(300),
    queued_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_email_messages_queue ON email_messages (tenant_id, status, queued_at);

ALTER TABLE messaging_settings ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE user_accounts ADD COLUMN email_verified_at TIMESTAMPTZ;

-- A code sent to an email address to prove it belongs to the person (hashed; a few attempts).
CREATE TABLE email_verifications (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES tenants (id),
    user_id      BIGINT        NOT NULL REFERENCES user_accounts (id),
    email        VARCHAR(120)  NOT NULL,
    code_hash    VARCHAR(100)  NOT NULL,
    expires_at   TIMESTAMPTZ   NOT NULL,
    attempts     SMALLINT      NOT NULL DEFAULT 0,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_verifications_user ON email_verifications (tenant_id, user_id);

CREATE TABLE report_schedules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    report_code     VARCHAR(20)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    filters         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    format          VARCHAR(4)    NOT NULL CHECK (format IN ('PDF', 'XLSX')),
    frequency       VARCHAR(8)    NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    day_of_week     SMALLINT      CHECK (day_of_week BETWEEN 1 AND 7),
    day_of_month    SMALLINT      CHECK (day_of_month BETWEEN 1 AND 28),
    send_hour       SMALLINT      NOT NULL DEFAULT 7 CHECK (send_hour BETWEEN 0 AND 23),
    recipients      VARCHAR(1000) NOT NULL,
    owner_id        BIGINT        NOT NULL REFERENCES user_accounts (id),
    owner_name      VARCHAR(200)  NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT true,
    next_run_at     TIMESTAMPTZ   NOT NULL,
    last_run_at     TIMESTAMPTZ,
    last_status     VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_report_schedules_day CHECK ((frequency <> 'WEEKLY' OR day_of_week IS NOT NULL) AND (frequency <> 'MONTHLY' OR day_of_month IS NOT NULL))
);
CREATE INDEX ix_report_schedules_due ON report_schedules (tenant_id, active, next_run_at);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['email_messages', 'email_verifications', 'report_schedules']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
