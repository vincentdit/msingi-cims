-- =====================================================================
-- CIMS V11: daily GePG reconciliation (FR-FIN-05, BP-06 steps 6–7).
--
-- GePG's daily file for a date is matched against the payments CIMS
-- received through the callback, by GePG receipt number (PayRefId).
-- Every line is kept (matched or not) as the audit trail; exceptions are
-- resolved by the Accountant, the day is closed, and the Bursar signs off
-- the month.
-- =====================================================================

CREATE TABLE gepg_reconciliations (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    recon_date        DATE          NOT NULL,
    source            VARCHAR(8)    NOT NULL CHECK (source IN ('FILE', 'API')),
    file_name         VARCHAR(200),
    status            VARCHAR(8)    NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    gepg_count        INT           NOT NULL DEFAULT 0,
    gepg_total        NUMERIC(16,2) NOT NULL DEFAULT 0,
    cims_count        INT           NOT NULL DEFAULT 0,
    cims_total        NUMERIC(16,2) NOT NULL DEFAULT 0,
    matched_count     INT           NOT NULL DEFAULT 0,
    exception_count   INT           NOT NULL DEFAULT 0,
    imported_at       TIMESTAMPTZ   NOT NULL,
    imported_by_name  VARCHAR(200)  NOT NULL,
    closed_at         TIMESTAMPTZ,
    closed_by_name    VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_gepg_reconciliations_date UNIQUE (tenant_id, recon_date),
    CONSTRAINT ck_gepg_reconciliations_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE gepg_reconciliation_items (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    reconciliation_id  BIGINT        NOT NULL REFERENCES gepg_reconciliations (id) ON DELETE CASCADE,
    line_no            INT,                          -- line in GePG's file; null for payments missing from it
    kind               VARCHAR(16)   NOT NULL CHECK (kind IN ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_IN_CIMS', 'MISSING_IN_GEPG',
                                                              'DUPLICATE', 'UNKNOWN_BILL')),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('MATCHED', 'OPEN', 'RESOLVED')),
    gepg_receipt_no    VARCHAR(40),
    bill_id            VARCHAR(40),
    control_no         VARCHAR(20),
    psp_name           VARCHAR(60),
    psp_receipt_no     VARCHAR(60),
    channel            VARCHAR(30),
    payer_phone        VARCHAR(20),
    gepg_amount        NUMERIC(14,2),
    cims_amount        NUMERIC(14,2),
    paid_at            TIMESTAMPTZ,
    payment_id         BIGINT        REFERENCES payments (id),
    paid_date_differs  BOOLEAN       NOT NULL DEFAULT FALSE,
    resolution         VARCHAR(12)   CHECK (resolution IN ('POSTED', 'NOTED', 'AUTO_MATCHED')),
    note               VARCHAR(500),
    resolved_at        TIMESTAMPTZ,
    resolved_by_name   VARCHAR(200),
    CONSTRAINT ck_gepg_items_resolved CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX ix_gepg_items_recon ON gepg_reconciliation_items (reconciliation_id, status);
CREATE INDEX ix_gepg_items_payment ON gepg_reconciliation_items (payment_id);

-- Bursar's monthly sign-off (BP-06 step 7)
CREATE TABLE reconciliation_signoffs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    month            DATE          NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    days_reconciled  INT           NOT NULL,
    total_collected  NUMERIC(16,2) NOT NULL,
    note             VARCHAR(500),
    signed_at        TIMESTAMPTZ   NOT NULL,
    signed_by        BIGINT        NOT NULL,
    signed_by_name   VARCHAR(200)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_reconciliation_signoffs UNIQUE (tenant_id, month)
);

CREATE INDEX ix_payments_paid_at ON payments (tenant_id, paid_at);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['gepg_reconciliations', 'gepg_reconciliation_items', 'reconciliation_signoffs']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
