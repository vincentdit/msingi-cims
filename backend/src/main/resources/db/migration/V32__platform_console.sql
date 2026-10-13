-- =====================================================================
-- CIMS V32: the Msingi platform console.
-- Platform staff (Msingi Technologies) sign in separately from any
-- institution and onboard, suspend and support institutions. Their
-- actions are kept in an append-only platform audit trail.
-- Calls to NECTA, NACTVET, GePG and the SMS gateway are logged so the
-- platform can watch integration health and each institution's
-- auditor can see its own calls.
-- =====================================================================

CREATE TABLE platform_users (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username              VARCHAR(60)   NOT NULL,
    full_name             VARCHAR(150)  NOT NULL,
    email                 VARCHAR(120),
    phone                 VARCHAR(20),
    password_hash         VARCHAR(200)  NOT NULL,
    status                VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_logins         INT           NOT NULL DEFAULT 0,
    must_change_password  BOOLEAN       NOT NULL DEFAULT TRUE,
    sessions_valid_after  TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_by_name       VARCHAR(150),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_platform_users_username ON platform_users (lower(username));

CREATE TABLE platform_audit_logs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor_id     BIGINT,
    actor_name   VARCHAR(150)  NOT NULL,
    action       VARCHAR(40)   NOT NULL,
    tenant_id    BIGINT        REFERENCES tenants (id),
    entity       VARCHAR(60)   NOT NULL,
    entity_id    BIGINT,
    details      JSONB,
    ip_address   VARCHAR(64)
);
CREATE INDEX ix_platform_audit_time ON platform_audit_logs (occurred_at DESC);
CREATE INDEX ix_platform_audit_tenant ON platform_audit_logs (tenant_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION cims_reject_platform_audit_change() RETURNS trigger LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION 'platform_audit_logs is append-only'; END $$;
CREATE TRIGGER trg_platform_audit_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_logs
    FOR EACH ROW EXECUTE FUNCTION cims_reject_platform_audit_change();

ALTER TABLE tenants
    ADD COLUMN status_reason      VARCHAR(300),
    ADD COLUMN status_changed_at  TIMESTAMPTZ,
    ADD COLUMN status_changed_by  VARCHAR(150),
    ADD COLUMN onboarded_by       VARCHAR(150),
    ADD COLUMN notes              VARCHAR(1000);

CREATE TABLE integration_calls (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        REFERENCES tenants (id),
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    system       VARCHAR(20)   NOT NULL CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'GEPG', 'SMS')),
    operation    VARCHAR(40)   NOT NULL,
    direction    VARCHAR(3)    NOT NULL CHECK (direction IN ('OUT', 'IN')),
    mode         VARCHAR(10)   NOT NULL CHECK (mode IN ('LIVE', 'SIMULATED')),
    outcome      VARCHAR(10)   NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    duration_ms  INT,
    reference    VARCHAR(120),
    detail       VARCHAR(500)
);
CREATE INDEX ix_integration_calls_time ON integration_calls (occurred_at DESC);
CREATE INDEX ix_integration_calls_tenant ON integration_calls (tenant_id, occurred_at DESC);
CREATE INDEX ix_integration_calls_system ON integration_calls (system, occurred_at DESC);

-- An institution sees only its own calls. The platform (no institution set) sees all of them,
-- including calls that belong to no institution (e.g. a GePG notification for an unknown bill).
ALTER TABLE integration_calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_calls FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_or_tenant ON integration_calls
    USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())
    WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant());
