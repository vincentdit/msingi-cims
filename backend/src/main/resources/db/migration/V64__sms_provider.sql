-- ===========================================================================================================================
-- Real SMS through a Tanzanian SMS provider (NextSMS or Beem), with each institution's own sender name.
--  * Msingi holds one account with the provider (platform_sms_settings, set in the Msingi console; secrets encrypted) and
--    resells SMS to the institutions through the bundles they already buy in CIMS.
--  * Each institution's sender name (e.g. BAHARI) must be registered with TCRA and the networks through the provider before use.
--    sms_sender_names follows each name from the institution's request to approval; until its name is approved, an
--    institution's messages go out under Msingi's default sender name.
--  * sms_provider_refs maps the provider's message id back to the institution and message, so a delivery report the provider
--    posts to the platform address can be applied without knowing the institution.
-- ===========================================================================================================================

CREATE TABLE platform_sms_settings (
    id                SMALLINT      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider          VARCHAR(10)   CHECK (provider IN ('NEXTSMS', 'BEEM')),
    environment       VARCHAR(10)   NOT NULL DEFAULT 'SANDBOX' CHECK (environment IN ('SANDBOX', 'LIVE')),
    active            BOOLEAN       NOT NULL DEFAULT FALSE,
    settings          TEXT,                          -- the provider's non-secret fields (JSON: username, API key …)
    secrets_enc       TEXT,                          -- the provider's secret fields (JSON), encrypted with the data key
    base_url          VARCHAR(200),                  -- only when the provider gives a different address than CIMS's default
    report_url        VARCHAR(200),
    default_sender    VARCHAR(11)   NOT NULL DEFAULT 'MSINGI',
    notify_token_enc  TEXT,                          -- the secret part of the delivery-report address given to the provider
    last_test_at      TIMESTAMPTZ,
    last_test_ok      BOOLEAN,
    last_test_note    VARCHAR(300),
    updated_by_name   VARCHAR(150),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
INSERT INTO platform_sms_settings (id) VALUES (1);

CREATE TABLE sms_sender_names (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    sender_id          VARCHAR(11)   NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    note               VARCHAR(300),
    requested_by_name  VARCHAR(150)  NOT NULL,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by_name    VARCHAR(150),
    decided_at         TIMESTAMPTZ,
    UNIQUE (tenant_id, sender_id)
);
ALTER TABLE sms_sender_names ENABLE ROW LEVEL SECURITY;
ALTER TABLE sms_sender_names FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_or_tenant ON sms_sender_names USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())
    WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant());

CREATE TABLE sms_provider_refs (
    provider      VARCHAR(10)   NOT NULL,
    provider_ref  VARCHAR(80)   NOT NULL,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    message_id    BIGINT        NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_ref)
);
CREATE INDEX ix_sms_provider_refs_created ON sms_provider_refs (created_at);
