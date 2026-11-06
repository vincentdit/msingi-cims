-- ===========================================================================================================================
-- NHIF's Student Registration API (v2, as NHIF documents it), replacing the placeholder exchange of V54:
--  * each institution is an NHIF registration agent with its own OAuth2 client (client id, secret, scope) and its SystemId and
--    SubSystemId; students are registered with NHIF (Form Four index number, NIDA number, photo), then applied for cards in a
--    batch (BatchNo id/startYear/endYear/serial). NHIF answers each card application with a control number and amount, the
--    student pays NHIF, and NHIF issues the card;
--  * NHIF tells CIMS of every step by calling back the institution's callback URL, with a token it obtains from CIMS using the
--    callback credentials the institution gives NHIF (client_credentials). Tokens are kept only as hashes.
-- The institution no longer bills the contribution itself: NHIF's control number is what the student pays. Covers billed by the
-- institution before (V54) keep working as they were.
-- ===========================================================================================================================

ALTER TABLE integration_settings
    ADD COLUMN nhif_client_id              VARCHAR(100),
    ADD COLUMN nhif_client_secret_enc      VARCHAR(500),
    ADD COLUMN nhif_scope                  VARCHAR(200),
    ADD COLUMN nhif_system_id              INTEGER,
    ADD COLUMN nhif_sub_system_id          INTEGER,
    -- NHIF's education levels: 1 Bachelor, 2 College, 3 Advanced Level, 4 Ordinary Level, 5 Primary Level
    ADD COLUMN nhif_education_level        SMALLINT     NOT NULL DEFAULT 2 CHECK (nhif_education_level BETWEEN 1 AND 5),
    ADD COLUMN nhif_callback_client_id     VARCHAR(60),
    ADD COLUMN nhif_callback_secret_hash   VARCHAR(100),
    ADD COLUMN nhif_callback_created_at    TIMESTAMPTZ;

ALTER TABLE nhif_registration_batches
    ADD COLUMN kind          VARCHAR(16)  NOT NULL DEFAULT 'REGISTRATION' CHECK (kind IN ('REGISTRATION', 'CARD_APPLICATION')),
    ADD COLUMN intake_code   VARCHAR(6),
    ADD COLUMN failures      TEXT;          -- what NHIF said about the items it did not take, as received
ALTER TABLE nhif_registration_batches ALTER COLUMN batch_ref TYPE VARCHAR(80);

ALTER TABLE health_covers DROP CONSTRAINT IF EXISTS health_covers_status_check;
-- INSTITUTION: READY → REGISTERED (NHIF holds the student) → APPLIED (card applied for) → AWAITING_PAYMENT (NHIF's control
-- number) → PAID (NHIF confirmed payment) → ACTIVE (card issued); REJECTED with NHIF's reason at any step. SUBMITTED is V54's.
ALTER TABLE health_covers ADD CONSTRAINT health_covers_status_check CHECK (status IN (
    'ACTIVE', 'INVALID', 'AWAITING_PAYMENT', 'READY', 'SUBMITTED', 'REJECTED', 'REGISTERED', 'APPLIED', 'PAID'));
ALTER TABLE health_covers
    ADD COLUMN application_batch_id  BIGINT        REFERENCES nhif_registration_batches (id),
    ADD COLUMN correlation_id        VARCHAR(40),       -- this cover's item in the card application
    ADD COLUMN category              SMALLINT      CHECK (category IN (1, 2)),   -- 1 new card (by NIDA number), 2 existing card
    ADD COLUMN nhif_control_no       VARCHAR(30),
    ADD COLUMN nhif_invoice_no       VARCHAR(40),
    ADD COLUMN nhif_amount           NUMERIC(12,2),
    ADD COLUMN nhif_paid_on          DATE,
    ADD COLUMN verification_code     VARCHAR(40);       -- NHIF's code from the last card check
CREATE UNIQUE INDEX uq_health_covers_correlation ON health_covers (tenant_id, correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX ix_health_covers_control_no ON health_covers (tenant_id, nhif_control_no) WHERE nhif_control_no IS NOT NULL;
CREATE INDEX ix_health_covers_application ON health_covers (application_batch_id);

-- Tokens CIMS issued to NHIF for its callbacks (hash only), valid an hour
CREATE TABLE nhif_callback_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES tenants (id),
    token_hash   VARCHAR(64)   NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_nhif_callback_tokens_expiry ON nhif_callback_tokens (expires_at);

-- Every callback NHIF made, as received (for the Registrar and support: what NHIF said, and what CIMS did with it)
CREATE TABLE nhif_callbacks (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES tenants (id),
    kind         VARCHAR(20)   NOT NULL CHECK (kind IN ('REGISTRATION', 'CARD_APPLICATION', 'PAYMENT', 'CARD_ISSUED', 'CHANGE_REQUEST', 'UNKNOWN')),
    reference    VARCHAR(80),
    payload      TEXT          NOT NULL,
    outcome      VARCHAR(10)   NOT NULL CHECK (outcome IN ('APPLIED', 'IGNORED', 'UNMATCHED')),
    note         VARCHAR(300),
    received_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_nhif_callbacks_time ON nhif_callbacks (tenant_id, received_at);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['nhif_callback_tokens', 'nhif_callbacks']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
