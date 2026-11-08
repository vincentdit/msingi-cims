-- ===========================================================================================================================
-- V72: award certificates can be revoked or replaced, every public verification is logged, and a sign-in lock-out ends by
-- itself after a while (a permanent lock only after repeated lock-outs).
-- ===========================================================================================================================

-- A temporary lock-out: the account opens again by itself at locked_until; NULL with status LOCKED means locked until unlocked.
ALTER TABLE user_accounts ADD COLUMN locked_until TIMESTAMPTZ;

-- Award certificates: revoked (the award withdrawn, or issued in error) or replaced (lost or damaged: a new number and code).
ALTER TABLE graduands
    ADD COLUMN certificate_status   VARCHAR(10)  NOT NULL DEFAULT 'VALID' CHECK (certificate_status IN ('VALID', 'REVOKED')),
    ADD COLUMN certificate_revoked_at  TIMESTAMPTZ,
    ADD COLUMN certificate_revoked_by  VARCHAR(150),
    ADD COLUMN certificate_revoke_reason VARCHAR(500),
    ADD COLUMN replacement_count    INTEGER      NOT NULL DEFAULT 0;

-- The earlier certificates of a graduand: their codes keep answering the verification page ("replaced" or "revoked").
CREATE TABLE certificate_history (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    graduand_id        BIGINT        NOT NULL REFERENCES graduands (id) ON DELETE CASCADE,
    certificate_no     VARCHAR(40)   NOT NULL,
    verification_code  VARCHAR(30)   NOT NULL,
    issued_at          TIMESTAMPTZ,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('REPLACED', 'REVOKED')),
    reason             VARCHAR(500),
    closed_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_by          VARCHAR(150)  NOT NULL
);
CREATE UNIQUE INDEX uq_certificate_history_code ON certificate_history (verification_code);
CREATE INDEX ix_certificate_history_graduand ON certificate_history (tenant_id, graduand_id);

-- Each check on the public verification page: what was asked, what was found; the address is kept only as a keyed hash.
CREATE TABLE document_verifications (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    code               VARCHAR(30)   NOT NULL,
    document_type      VARCHAR(30),                       -- ADMISSION_LETTER, TRANSCRIPT, AWARD_CERTIFICATE, ID_CARD; NULL when not found
    document_no        VARCHAR(60),
    result             VARCHAR(12)   NOT NULL CHECK (result IN ('VALID', 'NOT_VALID', 'NOT_FOUND')),
    client_hash        VARCHAR(64),
    verified_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_document_verifications_doc ON document_verifications (tenant_id, document_no);
CREATE INDEX ix_document_verifications_at ON document_verifications (tenant_id, verified_at);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['certificate_history', 'document_verifications']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
