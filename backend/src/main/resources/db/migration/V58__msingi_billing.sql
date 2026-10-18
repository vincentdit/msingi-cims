-- ===========================================================================================================================
-- Msingi billing: what Msingi Technologies charges each institution for CIMS, the invoices it issues, the payments it receives
-- (recorded by Msingi staff or reported by Msingi's payment collector), reminders and the statement of account.
--
-- Charges come from the institution's contract: a one-off set-up fee, an annual licence fee (invoiced in advance at the start
-- and on each anniversary), a fee per active student per year and a fee per paid application or a share of the application fees
-- collected (invoiced in arrears at the end of each usage cycle). VAT is added on the subtotal.
--
-- Row security: an institution sees only its own contract, invoices and payments (never drafts, through the service); the
-- platform console (no institution set) sees all.
-- ===========================================================================================================================

CREATE SEQUENCE msingi_invoice_seq;
CREATE SEQUENCE msingi_receipt_seq;

-- Msingi's own details printed on invoices and the accounts institutions pay into (one row).
CREATE TABLE platform_billing_settings (
    id              SMALLINT      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    company_name    VARCHAR(150)  NOT NULL DEFAULT 'Msingi Technologies Ltd',
    tin             VARCHAR(20),
    vrn             VARCHAR(20),
    address         VARCHAR(300),
    phone           VARCHAR(40),
    email           VARCHAR(120),
    website         VARCHAR(120),
    -- [{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"015...","branch":"Azikiwe"}, {"provider":"MPESA","accountNumber":"123456",...}]
    channels        JSONB         NOT NULL DEFAULT '[]',
    invoice_note    VARCHAR(1000),
    updated_by_name VARCHAR(150),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
INSERT INTO platform_billing_settings (id) VALUES (1);

CREATE TABLE billing_contracts (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT        NOT NULL REFERENCES tenants (id),
    reference               VARCHAR(60),                                  -- the signed contract's number
    status                  VARCHAR(8)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED')),
    start_date              DATE          NOT NULL,
    end_date                DATE,
    currency                CHAR(3)       NOT NULL DEFAULT 'TZS',
    setup_fee               NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (setup_fee >= 0),
    annual_fee              NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (annual_fee >= 0),
    student_fee             NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (student_fee >= 0),         -- per active student per year
    application_fee         NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (application_fee >= 0),     -- per application with the fee paid
    application_percent     NUMERIC(5,2)  NOT NULL DEFAULT 0 CHECK (application_percent BETWEEN 0 AND 100),  -- of application fees collected
    usage_cycle             VARCHAR(12)   NOT NULL DEFAULT 'QUARTERLY' CHECK (usage_cycle IN ('MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL')),
    vat_percent             NUMERIC(5,2)  NOT NULL DEFAULT 18 CHECK (vat_percent BETWEEN 0 AND 100),
    payment_terms_days      SMALLINT      NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    auto_issue              BOOLEAN       NOT NULL DEFAULT FALSE,         -- issue generated invoices without review
    auto_suspend            BOOLEAN       NOT NULL DEFAULT FALSE,         -- suspend the institution when an invoice is long overdue
    suspend_after_days      SMALLINT      NOT NULL DEFAULT 60 CHECK (suspend_after_days BETWEEN 7 AND 365),
    billing_contact_name    VARCHAR(150),
    billing_email           VARCHAR(120),
    billing_phone           VARCHAR(20),
    notes                   VARCHAR(2000),
    setup_billed            BOOLEAN       NOT NULL DEFAULT FALSE,
    licence_billed_until    DATE,                                         -- last day of the latest contract year invoiced
    usage_billed_until      DATE,                                         -- last day of the latest usage cycle invoiced
    created_by_name         VARCHAR(150),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE UNIQUE INDEX uq_billing_contract_active ON billing_contracts (tenant_id) WHERE status = 'ACTIVE';

CREATE TABLE billing_invoices (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL REFERENCES tenants (id),
    contract_id         BIGINT        REFERENCES billing_contracts (id),
    invoice_no          VARCHAR(30)   UNIQUE,                             -- given when issued, so numbers have no gaps
    kind                VARCHAR(8)    NOT NULL CHECK (kind IN ('LICENCE', 'USAGE', 'OTHER')),
    period_from         DATE,
    period_to           DATE,
    status              VARCHAR(12)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ISSUED', 'PARTLY_PAID', 'PAID', 'CANCELLED')),
    currency            CHAR(3)       NOT NULL DEFAULT 'TZS',
    vat_percent         NUMERIC(5,2)  NOT NULL DEFAULT 18,
    subtotal            NUMERIC(14,2) NOT NULL DEFAULT 0,
    vat_amount          NUMERIC(14,2) NOT NULL DEFAULT 0,
    total               NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    paid_amount         NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    issue_date          DATE,
    due_date            DATE,
    payment_reference   VARCHAR(12)   UNIQUE,                             -- quoted when paying online or at the bank
    notes               VARCHAR(1000),
    generated           BOOLEAN       NOT NULL DEFAULT FALSE,             -- prepared by the billing job
    reminder_level      SMALLINT      NOT NULL DEFAULT 0,
    last_reminded_at    TIMESTAMPTZ,
    cancelled_reason    VARCHAR(300),
    created_by_name     VARCHAR(150),
    issued_by_name      VARCHAR(150),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_billing_invoices_tenant ON billing_invoices (tenant_id, status);
CREATE INDEX ix_billing_invoices_due ON billing_invoices (due_date) WHERE status IN ('ISSUED', 'PARTLY_PAID');

CREATE TABLE billing_invoice_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id    BIGINT        NOT NULL REFERENCES billing_invoices (id) ON DELETE CASCADE,
    kind          VARCHAR(20)   NOT NULL CHECK (kind IN ('SETUP', 'LICENCE', 'STUDENTS', 'APPLICATIONS', 'APPLICATION_SHARE', 'OTHER', 'DISCOUNT')),
    description   VARCHAR(300)  NOT NULL,
    quantity      NUMERIC(12,2) NOT NULL DEFAULT 1,
    unit_price    NUMERIC(14,2) NOT NULL,
    amount        NUMERIC(14,2) NOT NULL,
    sort_order    SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_billing_lines_invoice ON billing_invoice_lines (invoice_id);

CREATE TABLE billing_payments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    receipt_no       VARCHAR(30)   NOT NULL UNIQUE,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    unallocated      NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (unallocated >= 0),   -- credit held for the next invoices
    paid_at          TIMESTAMPTZ   NOT NULL,
    method           VARCHAR(14)   NOT NULL CHECK (method IN ('BANK', 'MOBILE_MONEY', 'CHEQUE', 'CASH', 'OTHER')),
    provider         VARCHAR(40),                                         -- PaymentProvider code or a name
    reference        VARCHAR(80),                                         -- bank or mobile money reference
    transaction_key  VARCHAR(120)  UNIQUE,                                -- provider + transaction id: a repeated notification is ignored
    source           VARCHAR(8)    NOT NULL CHECK (source IN ('MANUAL', 'ONLINE')),
    status           VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'REVERSED')),
    note             VARCHAR(500),
    reversed_reason  VARCHAR(300),
    recorded_by_name VARCHAR(150),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_billing_payments_tenant ON billing_payments (tenant_id, paid_at);

CREATE TABLE billing_allocations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenants (id),
    payment_id  BIGINT        NOT NULL REFERENCES billing_payments (id) ON DELETE CASCADE,
    invoice_id  BIGINT        NOT NULL REFERENCES billing_invoices (id),
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_billing_allocations_invoice ON billing_allocations (invoice_id);
CREATE INDEX ix_billing_allocations_payment ON billing_allocations (payment_id);

-- Reminders sent for an invoice (one row per level, so a level is never sent twice).
CREATE TABLE billing_reminders (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id  BIGINT        NOT NULL REFERENCES billing_invoices (id) ON DELETE CASCADE,
    level       SMALLINT      NOT NULL,
    kind        VARCHAR(16)   NOT NULL CHECK (kind IN ('DUE_SOON', 'OVERDUE', 'SUSPENSION_DUE', 'SUSPENDED', 'MANUAL')),
    sent_to     VARCHAR(300),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_billing_reminders_invoice ON billing_reminders (invoice_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['billing_contracts', 'billing_invoices', 'billing_invoice_lines', 'billing_payments', 'billing_allocations', 'billing_reminders']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY platform_or_tenant ON %I USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())'
                       ' WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
