-- Mobile money through a payment aggregator (Selcom, AzamPay, ClickPesa) for private and faith-based institutions.
-- Each institution has its own merchant account with the aggregator and the money goes straight to the institution: CIMS keeps the
-- institution's credentials (secrets encrypted), asks the aggregator to prompt the payer's phone, and records the payment when the
-- aggregator confirms it. Statement import stays available for everything else.

CREATE TABLE collection_accounts (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL UNIQUE REFERENCES tenants (id),
    provider           VARCHAR(20)   NOT NULL CHECK (provider IN ('SIMULATED', 'SELCOM', 'AZAMPAY', 'CLICKPESA')),
    environment        VARCHAR(10)   NOT NULL DEFAULT 'SANDBOX' CHECK (environment IN ('SANDBOX', 'LIVE')),
    active             BOOLEAN       NOT NULL DEFAULT FALSE,
    phone_prompt       BOOLEAN       NOT NULL DEFAULT TRUE,
    settings           TEXT,                       -- the provider's non-secret fields (JSON: vendor, app name, client id …)
    secrets_enc        TEXT,                       -- the provider's secret fields (JSON), encrypted with the data key
    base_url           VARCHAR(200),               -- only when the provider gives a different address than CIMS's default
    auth_url           VARCHAR(200),
    notify_token_enc   TEXT,                       -- the secret part of the notification address CIMS gives the aggregator
    last_test_at       TIMESTAMPTZ,
    last_test_ok       BOOLEAN,
    last_test_note     VARCHAR(300),
    updated_by_name    VARCHAR(150),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One prompt sent to a payer's phone, and what became of it
CREATE TABLE phone_payment_requests (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id         BIGINT        NOT NULL REFERENCES invoices (id),
    reference          VARCHAR(40)   NOT NULL,     -- the invoice's CIMS payment reference
    provider           VARCHAR(20)   NOT NULL,
    order_id           VARCHAR(40)   NOT NULL,     -- CIMS's id for the request at the aggregator
    provider_ref       VARCHAR(80),                -- the aggregator's own id, when it gives one
    phone              VARCHAR(15)   NOT NULL,     -- 255XXXXXXXXX
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED')),
    message            VARCHAR(300),
    transaction_id     VARCHAR(80),
    channel            VARCHAR(40),
    requested_by       VARCHAR(10)   NOT NULL CHECK (requested_by IN ('APPLICANT', 'STUDENT', 'STAFF')),
    checks             INT           NOT NULL DEFAULT 0,
    last_checked_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    UNIQUE (tenant_id, order_id)
);
CREATE INDEX ix_phone_payment_requests_invoice ON phone_payment_requests (tenant_id, invoice_id, created_at);
CREATE INDEX ix_phone_payment_requests_pending ON phone_payment_requests (status, created_at) WHERE status = 'PENDING';

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['collection_accounts', 'phone_payment_requests']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
