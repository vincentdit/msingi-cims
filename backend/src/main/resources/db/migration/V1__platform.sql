-- =====================================================================
-- CIMS V1: platform schema — tenants, identity, OTP, numbering, audit
-- Every institution-owned table carries tenant_id and is protected by
-- row-level security (see V5__row_level_security.sql).
-- =====================================================================

CREATE TABLE tenants (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(20)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    short_name      VARCHAR(60),
    nactvet_reg_no  VARCHAR(40),
    ownership       VARCHAR(10)  NOT NULL DEFAULT 'PRIVATE' CHECK (ownership IN ('PUBLIC', 'PRIVATE')),
    status          VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED')),
    branding        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    contact_email   VARCHAR(120),
    contact_phone   VARCHAR(30),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE tenant_domains (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    host        VARCHAR(190) NOT NULL UNIQUE,
    purpose     VARCHAR(20)  NOT NULL DEFAULT 'ALL' CHECK (purpose IN ('ALL', 'ADMISSION', 'STUDENT', 'STAFF')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_tenant_domains_tenant ON tenant_domains (tenant_id);

CREATE TABLE user_accounts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id        UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id        BIGINT       NOT NULL REFERENCES tenants (id),
    username         VARCHAR(120) NOT NULL,
    email            VARCHAR(120),
    phone            VARCHAR(20),
    full_name        VARCHAR(200) NOT NULL,
    password_hash    VARCHAR(200) NOT NULL,
    user_type        VARCHAR(12)  NOT NULL CHECK (user_type IN ('STAFF', 'APPLICANT', 'STUDENT')),
    status           VARCHAR(25)  NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'DISABLED')),
    failed_logins    INT          NOT NULL DEFAULT 0,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_accounts_username UNIQUE (tenant_id, username)
);
CREATE UNIQUE INDEX uq_user_accounts_phone ON user_accounts (tenant_id, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_user_accounts_email ON user_accounts (tenant_id, lower(email)) WHERE email IS NOT NULL;

-- Role assignments with data scope (institution, campus, department, programme, ...)
CREATE TABLE user_roles (
    user_id     BIGINT      NOT NULL REFERENCES user_accounts (id) ON DELETE CASCADE,
    role        VARCHAR(40) NOT NULL,
    scope_type  VARCHAR(20) NOT NULL DEFAULT 'INSTITUTION',
    scope_id    BIGINT,
    PRIMARY KEY (user_id, role, scope_type)
);

CREATE TABLE otp_codes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    user_id     BIGINT       NOT NULL REFERENCES user_accounts (id) ON DELETE CASCADE,
    purpose     VARCHAR(30)  NOT NULL,
    code_hash   VARCHAR(200) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_codes_user ON otp_codes (user_id, purpose);

CREATE TABLE number_sequences (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants (id),
    name        VARCHAR(60) NOT NULL,
    next_value  BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT uq_number_sequences UNIQUE (tenant_id, name)
);

CREATE TABLE audit_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants (id),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id BIGINT,
    actor_name    VARCHAR(200),
    action        VARCHAR(40)  NOT NULL,
    entity        VARCHAR(60)  NOT NULL,
    entity_id     BIGINT,
    details       JSONB,
    ip_address    VARCHAR(64),
    correlation_id VARCHAR(64)
);
CREATE INDEX ix_audit_logs_entity ON audit_logs (tenant_id, entity, entity_id);
CREATE INDEX ix_audit_logs_time ON audit_logs (tenant_id, occurred_at DESC);
