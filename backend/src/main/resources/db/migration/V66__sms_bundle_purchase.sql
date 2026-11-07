-- ===========================================================================================================================
-- Institutions buy SMS bundles themselves and get them without anyone at Msingi acting:
--  * the institution chooses how many SMS; CIMS issues a Msingi invoice at once (kind SMS) with its payment reference;
--  * the institution pays it into Msingi's bank or mobile money accounts quoting the reference (reported by Msingi's payment
--    collector), or by a prompt on a phone through Msingi's own aggregator account;
--  * when the invoice is fully paid the bundle is credited to the institution's SMS balance automatically (fulfilled_at).
-- Msingi sets the price per SMS (for everyone, or per institution on its contract) and the smallest bundle.
-- ===========================================================================================================================

ALTER TABLE platform_billing_settings ADD COLUMN sms_price     NUMERIC(10,2) NOT NULL DEFAULT 25 CHECK (sms_price > 0);
ALTER TABLE platform_billing_settings ADD COLUMN sms_min_units INT           NOT NULL DEFAULT 500 CHECK (sms_min_units > 0);
ALTER TABLE platform_billing_settings ADD COLUMN sms_vat_percent NUMERIC(5,2) NOT NULL DEFAULT 18 CHECK (sms_vat_percent BETWEEN 0 AND 100);
ALTER TABLE billing_contracts ADD COLUMN sms_price NUMERIC(10,2) CHECK (sms_price IS NULL OR sms_price > 0);

ALTER TABLE billing_invoices DROP CONSTRAINT billing_invoices_kind_check;
ALTER TABLE billing_invoices ADD CONSTRAINT billing_invoices_kind_check CHECK (kind IN ('LICENCE', 'USAGE', 'OTHER', 'SMS'));
ALTER TABLE billing_invoices ADD COLUMN sms_units INT CHECK (sms_units IS NULL OR sms_units > 0);
ALTER TABLE billing_invoices ADD COLUMN fulfilled_at TIMESTAMPTZ;      -- when the bundle was credited
ALTER TABLE billing_invoice_lines DROP CONSTRAINT billing_invoice_lines_kind_check;
ALTER TABLE billing_invoice_lines ADD CONSTRAINT billing_invoice_lines_kind_check
    CHECK (kind IN ('SETUP', 'LICENCE', 'STUDENTS', 'APPLICATIONS', 'APPLICATION_SHARE', 'OTHER', 'DISCOUNT', 'SMS_BUNDLE'));

-- Msingi's own account with a payment aggregator, for prompting a payer's phone to pay a Msingi invoice (one row; no institution).
CREATE TABLE platform_collection_account (
    id                 SMALLINT      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider           VARCHAR(20)   CHECK (provider IN ('SIMULATED', 'SELCOM', 'AZAMPAY', 'CLICKPESA')),
    environment        VARCHAR(10)   NOT NULL DEFAULT 'SANDBOX' CHECK (environment IN ('SANDBOX', 'LIVE')),
    active             BOOLEAN       NOT NULL DEFAULT FALSE,
    settings           TEXT,
    secrets_enc        TEXT,
    base_url           VARCHAR(200),
    auth_url           VARCHAR(200),
    notify_token_enc   TEXT,
    last_test_at       TIMESTAMPTZ,
    last_test_ok       BOOLEAN,
    last_test_note     VARCHAR(300),
    updated_by_name    VARCHAR(150),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
INSERT INTO platform_collection_account (id) VALUES (1);

-- A prompt to a payer's phone for a Msingi invoice, and what became of it.
CREATE TABLE msingi_phone_payments (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id         BIGINT        NOT NULL REFERENCES billing_invoices (id),
    reference          VARCHAR(12)   NOT NULL,
    provider           VARCHAR(20)   NOT NULL,
    order_id           VARCHAR(40)   NOT NULL UNIQUE,
    provider_ref       VARCHAR(80),
    phone              VARCHAR(15)   NOT NULL,
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED')),
    message            VARCHAR(300),
    transaction_id     VARCHAR(80),
    channel            VARCHAR(40),
    checks             INT           NOT NULL DEFAULT 0,
    last_checked_at    TIMESTAMPTZ,
    requested_by_name  VARCHAR(150),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);
CREATE INDEX ix_msingi_phone_payments_pending ON msingi_phone_payments (status, created_at) WHERE status = 'PENDING';
CREATE INDEX ix_msingi_phone_payments_invoice ON msingi_phone_payments (invoice_id);
ALTER TABLE msingi_phone_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE msingi_phone_payments FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_or_tenant ON msingi_phone_payments USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())
    WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant());
