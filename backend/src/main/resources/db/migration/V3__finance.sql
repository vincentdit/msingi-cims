-- =====================================================================
-- CIMS V3: finance core — invoices, GePG control numbers, payments
-- Financial records are never deleted (see V5 grants note).
-- =====================================================================

CREATE TABLE invoices (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_no    VARCHAR(40)   NOT NULL,
    payer_type    VARCHAR(12)   NOT NULL CHECK (payer_type IN ('APPLICANT', 'STUDENT', 'SPONSOR')),
    payer_id      BIGINT        NOT NULL,
    payer_name    VARCHAR(200)  NOT NULL,
    payer_phone   VARCHAR(20),
    payer_email   VARCHAR(120),
    purpose       VARCHAR(30)   NOT NULL,       -- e.g. APPLICATION_FEE, TUITION
    reference_id  BIGINT,                       -- e.g. application id
    description   VARCHAR(250)  NOT NULL,
    currency      CHAR(3)       NOT NULL DEFAULT 'TZS',
    total_amount  NUMERIC(14,2) NOT NULL CHECK (total_amount >= 0),
    paid_amount   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    status        VARCHAR(16)   NOT NULL CHECK (status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED', 'CREDITED')),
    due_date      DATE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoices_no UNIQUE (tenant_id, invoice_no)
);
CREATE INDEX ix_invoices_payer ON invoices (tenant_id, payer_type, payer_id);
CREATE INDEX ix_invoices_reference ON invoices (tenant_id, purpose, reference_id);

CREATE TABLE invoice_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id    BIGINT        NOT NULL REFERENCES invoices (id),
    fee_item_code VARCHAR(30)   NOT NULL,
    description   VARCHAR(200)  NOT NULL,
    amount        NUMERIC(14,2) NOT NULL CHECK (amount >= 0)
);
CREATE INDEX ix_invoice_lines_invoice ON invoice_lines (invoice_id);

CREATE TABLE control_numbers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants (id),
    invoice_id  BIGINT      NOT NULL REFERENCES invoices (id),
    bill_id     VARCHAR(40) NOT NULL UNIQUE,    -- id we send to GePG: <tenantId>-<invoiceId>-<seq>
    control_no  VARCHAR(20) UNIQUE,
    status      VARCHAR(12) NOT NULL CHECK (status IN ('REQUESTED', 'ACTIVE', 'PAID', 'EXPIRED', 'FAILED')),
    issued_at   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_control_numbers_invoice ON control_numbers (invoice_id);

CREATE TABLE payments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id       BIGINT        NOT NULL REFERENCES invoices (id),
    control_no       VARCHAR(20)   NOT NULL,
    gepg_receipt_no  VARCHAR(40)   NOT NULL UNIQUE,   -- idempotency key for GePG notifications
    psp_receipt_no   VARCHAR(60),
    psp_name         VARCHAR(60),
    channel          VARCHAR(30),
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payer_phone      VARCHAR(20),
    paid_at          TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('RECEIVED', 'ALLOCATED', 'UNMATCHED', 'REVERSED')),
    receipt_no       VARCHAR(40),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_payments_invoice ON payments (invoice_id);
