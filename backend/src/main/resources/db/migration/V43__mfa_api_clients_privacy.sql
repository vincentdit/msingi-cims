-- BR-36 / FR-ADM-S02 / NFR-SEC-01: authenticator-app MFA for staff; NFR-SEC-02: idle time-outs per user type;
-- BR-41 / FR-INT-09: API clients (OAuth2 client credentials); BR-38 / FR-ADM-S06 / NFR-PRV-01: data subject requests and retention.

ALTER TABLE user_accounts ADD COLUMN mfa_secret          VARCHAR(200);   -- encrypted (AES-GCM) TOTP secret
ALTER TABLE user_accounts ADD COLUMN mfa_pending_secret  VARCHAR(200);   -- encrypted, until the first code confirms it
ALTER TABLE user_accounts ADD COLUMN mfa_enabled_at      TIMESTAMPTZ;
ALTER TABLE user_accounts ADD COLUMN mfa_recovery_codes  TEXT;           -- JSON list of bcrypt hashes, each usable once
ALTER TABLE user_accounts ADD COLUMN anonymised_at       TIMESTAMPTZ;

CREATE TABLE security_settings (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT      NOT NULL UNIQUE REFERENCES tenants (id),
    mfa_policy               VARCHAR(12) NOT NULL DEFAULT 'OPTIONAL' CHECK (mfa_policy IN ('OPTIONAL', 'PRIVILEGED', 'ALL_STAFF')),
    staff_idle_minutes       SMALLINT    NOT NULL DEFAULT 30 CHECK (staff_idle_minutes BETWEEN 5 AND 480),
    student_idle_minutes     SMALLINT    NOT NULL DEFAULT 60 CHECK (student_idle_minutes BETWEEN 5 AND 480),
    applicant_idle_minutes   SMALLINT    NOT NULL DEFAULT 60 CHECK (applicant_idle_minutes BETWEEN 5 AND 480),
    retention_applicant_months SMALLINT  NOT NULL DEFAULT 24 CHECK (retention_applicant_months BETWEEN 6 AND 120),
    retention_message_months   SMALLINT  NOT NULL DEFAULT 24 CHECK (retention_message_months BETWEEN 3 AND 120),
    retention_enabled        BOOLEAN     NOT NULL DEFAULT false,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_clients (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants (id),
    client_id      VARCHAR(60)   NOT NULL UNIQUE,     -- carries the institution, so the token endpoint needs no host
    name           VARCHAR(150)  NOT NULL,
    secret_hash    VARCHAR(100)  NOT NULL,
    scopes         VARCHAR(300)  NOT NULL,
    active         BOOLEAN       NOT NULL DEFAULT true,
    created_by     VARCHAR(200)  NOT NULL,
    last_used_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE data_subject_requests (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    request_no       VARCHAR(40)   NOT NULL,
    user_id          BIGINT        NOT NULL REFERENCES user_accounts (id),
    subject_name     VARCHAR(200)  NOT NULL,
    kind             VARCHAR(12)   NOT NULL CHECK (kind IN ('ACCESS', 'CORRECTION', 'ERASURE', 'OBJECTION')),
    details          VARCHAR(2000) NOT NULL,
    status           VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'REJECTED')),
    due_on           DATE          NOT NULL,
    response         VARCHAR(2000),
    handled_by_name  VARCHAR(200),
    handled_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_dsr_no UNIQUE (tenant_id, request_no)
);

CREATE TABLE retention_runs (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT       NOT NULL REFERENCES tenants (id),
    ran_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ran_by                 VARCHAR(200) NOT NULL,
    applicants_anonymised  INT          NOT NULL,
    messages_cleared       INT          NOT NULL,
    emails_cleared         INT          NOT NULL
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['security_settings', 'api_clients', 'data_subject_requests', 'retention_runs']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
